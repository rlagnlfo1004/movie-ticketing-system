#!/usr/bin/env python3
"""Render seven host-local Compose bundles with private-only service addresses."""
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[2]
REMOTE_ROOT = Path("/home/ec2-user/movie-ticketing")
ROLES = {
    "lg": ("k6",),
    "edge": ("nginx", "nginx-exporter"),
    "app-waiting": ("waiting-api-1", "waiting-api-2", "waiting-api-3"),
    "app-reservation": ("mock-pg", "reservation-worker", "reservation-api"),
    "redis": ("redis", "redis-exporter"),
    "mysql": ("mysql", "mysqld-exporter"),
    "mon": ("prometheus", "grafana", "renderer"),
}
SECRET_KEYS = {"MYSQL_PASSWORD", "MYSQL_ROOT_PASSWORD", "SPRING_DATASOURCE_PASSWORD",
               "MOCK_PG_API_SECRET", "MOCK_PG_WEBHOOK_SECRET", "RESERVATION_MOCK_PG_API_SECRET",
               "RESERVATION_WEBHOOK_SECRET", "RESERVATION_RECONCILIATION_KEY", "GRAFANA_ADMIN_PASSWORD"}


def read_env(path):
    return dict(line.split("=", 1) for line in Path(path).read_text().splitlines()
                if line and not line.startswith("#") and "=" in line)


def copy_file(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def write_text(destination, contents):
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(contents)


def prepare_shared(hosts, stage, tag, bundle, role):
    files = {
        "lg": ("load-test/load-test.js",),
        "edge": ("nginx/nginx.conf",),
        "app-waiting": ("infra/tuning/jvm.env",),
        "app-reservation": ("infra/tuning/jvm.env",),
        "redis": ("infra/tuning/redis-v1.conf", "infra/tuning/redis-v6.conf",
                  "infra/tuning/redis-v6-always.conf"),
        "mysql": ("infra/tuning/mysql-stage.cnf",),
        "mon": ("observability/prometheus.yml",),
    }
    for rel in files[role]:
        copy_file(ROOT / rel, bundle / rel)
    if role == "app-reservation":
        copy_file(ROOT / ".local/stage/mock-pg.mjs", bundle / "load-test/mock-pg.mjs")
        copy_file(ROOT / "load-test/mock-pg.Dockerfile", bundle / "load-test/mock-pg.Dockerfile")
    modules = {"app-waiting": ("waiting-api",),
               "app-reservation": ("reservation-api", "reservation-worker")}.get(role, ())
    if role == "app-reservation" and tag.startswith(("v1-", "v2-")):
        modules = ("reservation-api",)
    for service in modules:
        jar = ROOT / service / "build/libs" / f"{service}-0.0.1-SNAPSHOT.jar"
        if jar.exists():
            copy_file(jar, bundle / service / "build/libs" / jar.name)
    if role == "mon":
        source = ROOT / "observability/grafana/provisioning"
        shutil.copytree(source, bundle / "observability/grafana/provisioning", dirs_exist_ok=True)
    if role == "edge":
        nginx = (bundle / "nginx/nginx.conf").read_text()
        nginx = nginx.replace("waiting-api-1:8080", hosts["APP_WAITING_PRIVATE_IP"] + ":8080")
        nginx = nginx.replace("waiting-api-2:8080", hosts["APP_WAITING_PRIVATE_IP"] + ":8082")
        nginx = nginx.replace("waiting-api-3:8080", hosts["APP_WAITING_PRIVATE_IP"] + ":8083")
        nginx = nginx.replace("reservation-api:8081", hosts["APP_RESERVATION_PRIVATE_IP"] + ":8081")
        write_text(bundle / "nginx/nginx.conf", nginx)
    if role != "mon":
        return
    prometheus = (bundle / "observability/prometheus.yml").read_text()
    replacements = {
        "prometheus:9090": "127.0.0.1:9090",
        "waiting-api-1:8080": hosts["APP_WAITING_PRIVATE_IP"] + ":8080",
        "waiting-api-2:8080": hosts["APP_WAITING_PRIVATE_IP"] + ":8082",
        "waiting-api-3:8080": hosts["APP_WAITING_PRIVATE_IP"] + ":8083",
        "reservation-api:8081": hosts["APP_RESERVATION_PRIVATE_IP"] + ":8081",
        "reservation-worker:8084": hosts["APP_RESERVATION_PRIVATE_IP"] + ":8084",
        "mysqld-exporter:9104": hosts["MYSQL_PRIVATE_IP"] + ":9104",
        "redis-exporter:9121": hosts["REDIS_PRIVATE_IP"] + ":9121",
        "nginx-exporter:9113": hosts["EDGE_PRIVATE_IP"] + ":9113",
    }
    for old, new in replacements.items():
        prometheus = prometheus.replace(old, new)
    nodes = ", ".join(hosts[key] + ":9100" for key in (
        "LG_PRIVATE_IP", "EDGE_PRIVATE_IP", "APP_WAITING_PRIVATE_IP",
        "APP_RESERVATION_PRIVATE_IP", "REDIS_PRIVATE_IP", "MYSQL_PRIVATE_IP", "MON_PRIVATE_IP"))
    prometheus = prometheus.replace("[node-exporter:9100]", f"[{nodes}]")
    write_text(bundle / "observability/prometheus.yml", prometheus)


def service_config(base, role, hosts, secrets, profile):
    selected = ROLES[role]
    if profile in ("v1", "v2"):
        selected = tuple(s for s in selected if s != "reservation-worker")
    services = {}
    for name in selected:
        value = copy.deepcopy(base["services"][name])
        for key in ("depends_on", "networks", "ports", "links"):
            value.pop(key, None)
        value["network_mode"] = "host"
        value["volumes"] = [
            {**volume, "source": str(REMOTE_ROOT / Path(volume["source"]).relative_to(ROOT))}
            if volume.get("type") == "bind" and Path(volume["source"]).is_relative_to(ROOT)
            else volume for volume in value.get("volumes", [])]
        environment = value.get("environment", {})
        environment.update({key: value for key, value in secrets.items() if key in environment})
        if name.startswith("waiting-api-"):
            environment["SPRING_DATA_REDIS_HOST"] = hosts["REDIS_PRIVATE_IP"]
            if name != "waiting-api-1":
                port = "8082" if name.endswith("-2") else "8083"
                environment["SERVER_PORT"] = port
                value["healthcheck"]["test"] = ["CMD-SHELL", f"wget -qO- http://localhost:{port}/actuator/health | grep -q UP || exit 1"]
        elif name in ("reservation-api", "reservation-worker"):
            environment.update({key: val for key, val in secrets.items()
                                if key.startswith("SPRING_") or key.startswith("RESERVATION_")})
            environment["SPRING_DATA_REDIS_HOST"] = hosts["REDIS_PRIVATE_IP"]
        elif name == "k6":
            environment.update({"BASE_URL": "http://" + hosts["EDGE_PRIVATE_IP"],
                "MOCK_PG_URL": "http://" + hosts["APP_RESERVATION_PRIVATE_IP"] + ":8082",
                "WEBHOOK_URL": "http://" + hosts["APP_RESERVATION_PRIVATE_IP"] + ":8081/api/v1/webhooks/portone",
                "K6_PROMETHEUS_RW_SERVER_URL": "http://" + hosts["MON_PRIVATE_IP"] + ":9090/api/v1/write"})
        elif name == "redis-exporter":
            environment["REDIS_ADDR"] = "redis://127.0.0.1:6379"
        elif name == "grafana":
            environment.update({"GF_SECURITY_ADMIN_PASSWORD": "${GRAFANA_ADMIN_PASSWORD}",
                "GF_RENDERING_SERVER_URL": "http://127.0.0.1:8081/render",
                "GF_RENDERING_CALLBACK_URL": "http://127.0.0.1:3000/"})
        for key in SECRET_KEYS:
            if key in environment and key != "GRAFANA_ADMIN_PASSWORD":
                environment[key] = "${" + key + "}"
        if name == "mysql":
            for volume in value.get("volumes", []):
                if volume.get("target") == "/etc/mysql/conf.d/tuning.cnf":
                    volume["source"] = str(REMOTE_ROOT / "infra/tuning/mysql-stage.cnf")
            value.setdefault("volumes", []).append({"type": "bind", "source": "/mnt/movie-mysql", "target": "/var/lib/mysql"})
        if name == "mock-pg":
            value["build"] = {"context": str(REMOTE_ROOT), "dockerfile": "load-test/mock-pg.Dockerfile"}
        if name == "nginx-exporter":
            value["command"] = ["--nginx.scrape-uri=http://127.0.0.1/nginx_status"]
        services[name] = value
    return {"name": "movie-measurement", "services": services,
            "volumes": {"redis-data": {}} if role == "redis" else {}}


def main(hosts_file, profile, destination):
    hosts = read_env(hosts_file)
    stage = read_env(ROOT / "infra/stages" / f"{profile}.env")
    fixture_tag = ROOT / ".local/stage/tag.txt"
    if not fixture_tag.exists() or fixture_tag.read_text().strip() != stage["STAGE_CODE_TAG"]:
        raise SystemExit("Stage mock PG fixture does not match the requested git tag")
    if os.environ.get("VIRTUAL_OFF") == "1":
        stage["SPRING_THREADS_VIRTUAL_ENABLED"] = "false"
    environment = {**os.environ, **stage}
    rendered = subprocess.run(["docker", "compose", "-f", str(ROOT / "docker-compose.yml"),
                               "--profile", "load-test",
                               "config", "--format", "json"], cwd=ROOT, env=environment,
                              capture_output=True, text=True, check=True)
    base = json.loads(rendered.stdout)
    output = Path(destination)
    output.mkdir(parents=True, exist_ok=True)
    output.chmod(0o700)
    for role in ROLES:
        bundle = output / role
        if bundle.exists():
            shutil.rmtree(bundle)
        bundle.mkdir(parents=True, exist_ok=True)
        bundle.chmod(0o700)
        prepare_shared(hosts, stage, stage["STAGE_CODE_TAG"], bundle, role)
        secrets = read_env(output / f"{role}.secret.env")
        if os.environ.get("VIRTUAL_OFF") == "1":
            secrets["SPRING_THREADS_VIRTUAL_ENABLED"] = "false"
        config = service_config(base, role, hosts, secrets, profile)
        write_text(bundle / "compose.json", json.dumps(config, indent=2))
        if role == "mysql":
            local = read_env(ROOT / ".env")
            exporter = ("[client]\nuser=root\npassword=" + local["SEAT_DB_ROOT_PASSWORD"]
                        + "\nhost=127.0.0.1\nport=3306\n")
            write_text(bundle / ".local/mysql-exporter.cnf", exporter)
            (bundle / ".local/mysql-exporter.cnf").chmod(0o600)
        with tarfile.open(output / f"{role}.tar.gz", "w:gz") as archive:
            for path in bundle.rglob("*"):
                if path.is_file():
                    archive.add(path, arcname=str(path.relative_to(bundle)))
        (output / f"{role}.tar.gz").chmod(0o600)
        print(output / f"{role}.tar.gz")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: build-stage-bundles.py <hosts.env> <stage-profile> <output-dir>")
    main(*sys.argv[1:])
