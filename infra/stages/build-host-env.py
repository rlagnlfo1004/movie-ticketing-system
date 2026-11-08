#!/usr/bin/env python3
"""Combine Terraform hosts, one stage, and local secrets into per-host env files."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
HOSTS = ("lg", "edge", "app-waiting", "app-reservation", "redis", "mysql", "mon")


def read_env(path):
    result = {}
    for line in path.read_text().splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def write_env(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(f"{key}={value}\n" for key, value in sorted(data.items())))
    path.chmod(0o600)


def main(hosts_path, profile, destination):
    hosts = read_env(Path(hosts_path))
    stage = read_env(ROOT / "infra/stages" / f"{profile}.env")
    local = read_env(ROOT / ".env")
    grafana = read_env(Path.home() / ".config/movie-ticketing/loadtest-grafana.env")
    if stage.get("PAYMENT_MODE") != "mock":
        raise SystemExit("AWS measurement accepts only the mock PG mode")
    private_keys = ("LG_PRIVATE_IP", "EDGE_PRIVATE_IP", "APP_WAITING_PRIVATE_IP",
                    "APP_RESERVATION_PRIVATE_IP", "REDIS_PRIVATE_IP", "MYSQL_PRIVATE_IP", "MON_PRIVATE_IP")
    for key in private_keys:
        if not hosts.get(key):
            raise SystemExit(f"Missing {key} in hosts.env")
    common = {key: value for key, value in stage.items() if key != "STAGE_CODE_TAG"}
    db_url = f"jdbc:mysql://{hosts['MYSQL_PRIVATE_IP']}:3306/{local['SEAT_DB_NAME']}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    data = {
        "lg": {**common, "BASE_URL": f"http://{hosts['EDGE_PRIVATE_IP']}"},
        "edge": {**common, "WAITING_PRIVATE_IP": hosts["APP_WAITING_PRIVATE_IP"],
                 "RESERVATION_PRIVATE_IP": hosts["APP_RESERVATION_PRIVATE_IP"]},
        "app-waiting": {**common, "SPRING_DATA_REDIS_HOST": hosts["REDIS_PRIVATE_IP"]},
        "app-reservation": {**common, "SPRING_DATA_REDIS_HOST": hosts["REDIS_PRIVATE_IP"],
                            "SPRING_DATASOURCE_URL": db_url,
                            "RESERVATION_ACTIVE_ADMISSION_BASE_URL": f"http://{hosts['APP_WAITING_PRIVATE_IP']}:8080",
                            "SPRING_DATASOURCE_USERNAME": local["SEAT_DB_USER"],
                            "SPRING_DATASOURCE_PASSWORD": local["SEAT_DB_PASSWORD"],
                            "RESERVATION_MOCK_PG_BASE_URL": "http://127.0.0.1:8082",
                            "MOCK_PG_API_SECRET": local["MOCK_PG_API_SECRET"],
                            "MOCK_PG_WEBHOOK_SECRET": local["MOCK_PG_WEBHOOK_SECRET"],
                            "RESERVATION_MOCK_PG_API_SECRET": local["RESERVATION_MOCK_PG_API_SECRET"],
                            "RESERVATION_WEBHOOK_SECRET": local["RESERVATION_WEBHOOK_SECRET"],
                            "RESERVATION_RECONCILIATION_KEY": local["RESERVATION_RECONCILIATION_KEY"]},
        "redis": common,
        "mysql": {**common, "MYSQL_DATABASE": local["SEAT_DB_NAME"],
                  "MYSQL_USER": local["SEAT_DB_USER"],
                  "MYSQL_PASSWORD": local["SEAT_DB_PASSWORD"],
                  "MYSQL_ROOT_PASSWORD": local["SEAT_DB_ROOT_PASSWORD"]},
        "mon": {**common, "GRAFANA_ADMIN_PASSWORD": grafana["GRAFANA_ADMIN_PASSWORD"],
                "MYSQL_PRIVATE_IP": hosts["MYSQL_PRIVATE_IP"],
                "REDIS_PRIVATE_IP": hosts["REDIS_PRIVATE_IP"],
                "EDGE_PRIVATE_IP": hosts["EDGE_PRIVATE_IP"],
                "APP_WAITING_PRIVATE_IP": hosts["APP_WAITING_PRIVATE_IP"],
                "APP_RESERVATION_PRIVATE_IP": hosts["APP_RESERVATION_PRIVATE_IP"]},
    }
    output = Path(destination)
    for host in HOSTS:
        path = output / f"{host}.secret.env"
        write_env(path, data[host])
        print(path)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: build-host-env.py <hosts.env> <stage-profile> <output-dir>")
    main(*sys.argv[1:])
