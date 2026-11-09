#!/usr/bin/env python3
"""Deploy a stage to seven hosts and collect each run before any teardown."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shlex
import subprocess
import time
from urllib.request import urlopen

from run_stage import ROOT, build_tag, stage_settings, verify_run, verify_payment_balance, verify_artifact_secrets
from local_env import get as local_env

REMOTE_ROOT = "/home/ec2-user/movie-ticketing"
ORDER = ("redis", "mysql", "app-waiting", "app-reservation", "edge", "mon")
ROLES = ("lg", "edge", "app-waiting", "app-reservation", "redis", "mysql", "mon")


def read_env(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line)


def public(hosts, role):
    return hosts[role.upper().replace("-", "_") + "_PUBLIC_IP"]


def remote(hosts, key, role, *command, check=True, capture=False, input=None):
    address = f"ec2-user@{public(hosts, role)}"
    args = ["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
            "-i", str(key), address, shlex.join(command)]
    result = subprocess.run(args, input=input, cwd=ROOT, capture_output=capture, check=False)
    if check and result.returncode:
        raise RuntimeError(f"Remote command failed on {role}: exit {result.returncode}")
    return result


def compose(hosts, key, role, *args, check=True, capture=False):
    return remote(hosts, key, role, "docker", "compose", "-f", REMOTE_ROOT + "/compose.json", *args,
                  check=check, capture=capture)


def wait_url(url, seconds=150):
    for _ in range(seconds):
        try:
            with urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError(f"Remote service did not become ready: {url}")


def wait_healthy(hosts, key, role, service, seconds=120):
    for _ in range(seconds):
        found = compose(hosts, key, role, "ps", "-q", service, check=False, capture=True)
        container = found.stdout.decode().strip()
        if container:
            status = remote(hosts, key, role, "docker", "inspect", "--format", "{{.State.Health.Status}}",
                            container, check=False, capture=True)
            if status.stdout.strip() == b"healthy":
                return
        time.sleep(1)
    raise RuntimeError(f"Remote {service} did not become healthy")


def tunnel(hosts, key, role, forwards):
    args = ["ssh", "-N", "-o", "BatchMode=yes", "-o", "ExitOnForwardFailure=yes",
            "-o", "StrictHostKeyChecking=accept-new",
            "-i", str(key)]
    for local, remote_port in forwards:
        args += ["-L", f"127.0.0.1:{local}:127.0.0.1:{remote_port}"]
    args.append(f"ec2-user@{public(hosts, role)}")
    return subprocess.Popen(args, cwd=ROOT)


def deploy(hosts_file, hosts, key, profile, output, virtual_off=False):
    environment = {**os.environ, "SSH_USER": "ec2-user"}
    if virtual_off:
        environment["VIRTUAL_OFF"] = "1"
    subprocess.run([str(ROOT / "infra/stages/upload-host-env.sh"), str(hosts_file), profile,
                    str(output), str(key)], cwd=ROOT, env=environment, check=True)
    subprocess.run(["python3", str(ROOT / "infra/aws/build-stage-bundles.py"),
                    str(hosts_file), profile, str(output)], cwd=ROOT, env=environment, check=True)
    for role in ROLES:
        target = f"ec2-user@{public(hosts, role)}:{REMOTE_ROOT}/stage.tar.gz"
        subprocess.run(["scp", "-q", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
                        "-i", str(key), str(output / f"{role}.tar.gz"), target], cwd=ROOT, check=True)
        remote(hosts, key, role, "chmod", "600", REMOTE_ROOT + "/stage.tar.gz")
        remote(hosts, key, role, "tar", "-xzf", REMOTE_ROOT + "/stage.tar.gz", "-C", REMOTE_ROOT)
        remote(hosts, key, role, "chmod", "600", REMOTE_ROOT + "/compose.json")
        remote(hosts, key, role, "rm", "-f", REMOTE_ROOT + "/stage.tar.gz")
    remote(hosts, key, "mysql", "mountpoint", "-q", "/mnt/movie-mysql")


def reset(hosts, key):
    for role in reversed(ORDER):
        compose(hosts, key, role, "down", "--volumes", "--remove-orphans", check=False)
    remote(hosts, key, "mysql", "sudo", "find", "/mnt/movie-mysql", "-mindepth", "1",
           "-maxdepth", "1", "-exec", "rm", "-rf", "{}", "+")
    for role in ORDER:
        if role == "app-reservation":
            compose(hosts, key, role, "up", "-d", "--build", "--no-deps", "mock-pg")
            wait_healthy(hosts, key, role, "mock-pg")
            services = compose(hosts, key, role, "config", "--services", capture=True).stdout.decode().splitlines()
            if "reservation-worker" in services:
                compose(hosts, key, role, "up", "-d", "--no-deps", "reservation-worker")
                wait_healthy(hosts, key, role, "reservation-worker")
            compose(hosts, key, role, "up", "-d", "--no-deps", "reservation-api")
            wait_url("http://127.0.0.1:18081/actuator/health/readiness")
        elif role == "mysql":
            compose(hosts, key, role, "up", "-d", "--no-deps", "mysql")
            wait_healthy(hosts, key, role, "mysql")
            compose(hosts, key, role, "up", "-d", "--no-deps", "mysqld-exporter")
        else:
            compose(hosts, key, role, "up", "-d", "--build", "--no-deps")
        if role == "redis":
            for _ in range(30):
                flushed = compose(hosts, key, "redis", "exec", "-T", "redis", "redis-cli", "FLUSHALL",
                                  check=False, capture=True)
                if flushed.returncode == 0 and flushed.stdout.strip() == b"OK":
                    break
                time.sleep(1)
            else:
                raise RuntimeError("Remote Redis FLUSHALL failed")
    wait_url("http://127.0.0.1:13000/api/health")
    wait_url("http://127.0.0.1:19090/-/ready")


def seed(hosts, key, seconds):
    generated = subprocess.run(["python3", str(ROOT / "load-test/seed-admissions.py")],
                               cwd=ROOT, env={**os.environ, "STAGE_SECONDS": str(seconds), "SCREENING_ID": "1"},
                               capture_output=True, check=True)
    remote(hosts, key, "redis", "docker", "compose", "-f", REMOTE_ROOT + "/compose.json",
           "exec", "-T", "redis", "redis-cli", "--pipe", input=generated.stdout)


def verify_seat_uniqueness(hosts, key):
    sql = ("SELECT COUNT(*) FROM (SELECT screening_id,seat_no FROM reservation_seats "
           "GROUP BY screening_id,seat_no HAVING COUNT(*)>1) duplicates")
    response = remote(hosts, key, "mysql", "docker", "compose", "-f", REMOTE_ROOT + "/compose.json",
                      "exec", "-T", "-e", "MYSQL_PWD=" + local_env("SEAT_DB_ROOT_PASSWORD"),
                      "mysql", "mysql", "-uroot", "-N", "-B", "movie_seats", "-e", sql,
                      capture=True)
    if response.stdout.strip() != b"0":
        raise RuntimeError("Reservation seats contain duplicates")


def jfr_start(hosts, key):
    compose(hosts, key, "app-reservation", "exec", "-T", "reservation-api", "jcmd", "1",
            "JFR.start", "name=measurement", "settings=profile")


def jfr_save(hosts, key, stage, name):
    compose(hosts, key, "app-reservation", "exec", "-T", "reservation-api", "jcmd", "1",
            "JFR.dump", "name=measurement", f"filename=/app/{name}-raw.jfr")
    compose(hosts, key, "app-reservation", "exec", "-T", "reservation-api", "jcmd", "1",
            "JFR.stop", "name=measurement")
    try:
        compose(hosts, key, "app-reservation", "exec", "-T", "reservation-api", "jfr", "scrub",
                "--include-events", "jdk.VirtualThreadPinned", f"/app/{name}-raw.jfr", f"/app/{name}.jfr")
        destination = ROOT / "docs/evidence" / stage
        destination.mkdir(parents=True, exist_ok=True)
        subprocess.run(["scp", "-q", "-i", str(key),
                        f"ec2-user@{public(hosts, 'app-reservation')}:{REMOTE_ROOT}/reservation-api/build/libs/{name}.jfr",
                        str(destination / f"{name}.jfr")], cwd=ROOT, check=True)
        printed = compose(hosts, key, "app-reservation", "exec", "-T", "reservation-api",
                          "jfr", "print", "--events", "jdk.VirtualThreadPinned", f"/app/{name}.jfr",
                          capture=True)
        (destination / f"{name}-pinning.txt").write_bytes(
            printed.stdout or b"No jdk.VirtualThreadPinned events in this recording.\n")
    finally:
        remote(hosts, key, "app-reservation", "rm", "-f",
               f"{REMOTE_ROOT}/reservation-api/build/libs/{name}-raw.jfr", check=False)


def measure(hosts, key, env, stage, profile, run_id, scenario, smoke, fault=None, jfr_name=None):
    from remote_fault import inject
    directory = ROOT / "load-test/results" / stage / run_id
    directory.mkdir(parents=True, exist_ok=True)
    subprocess.run(["python3", str(ROOT / "load-test/capture.py"), "snapshot", stage, run_id, "before"],
                   cwd=ROOT, env=env, check=True)
    if scenario == "B":
        seed(hosts, key, 1 if smoke else 60)
    subprocess.run([str(ROOT / "load-test/snapshot.sh"), stage, f"{run_id}-before"],
                   cwd=ROOT, env=env, check=True)
    if jfr_name:
        jfr_start(hosts, key)
    result_path = REMOTE_ROOT + f"/results/{stage}/{run_id}"
    remote(hosts, key, "lg", "mkdir", "-p", result_path)
    remote(hosts, key, "lg", "chmod", "0777", result_path)
    command = ["docker", "compose", "-f", REMOTE_ROOT + "/compose.json", "--profile", "load-test",
               "run", "--rm", "--no-deps", "-v", REMOTE_ROOT + f"/results/{stage}/{run_id}:/results",
               "k6", "run", "-o", "experimental-prometheus-rw", "-e", f"SCENARIO={scenario}",
               "-e", "SCREENING_ID=1", "-e", f"STAGE_SECONDS={1 if smoke else 60}",
               "-e", f"SMOKE={1 if smoke else 0}", "--tag", f"testid={run_id}",
               "--summary-export=/results/summary.json", "/load-test/load-test.js"]
    started = time.time()
    process = subprocess.Popen(["ssh", "-o", "BatchMode=yes", "-i", str(key),
                                f"ec2-user@{public(hosts, 'lg')}", shlex.join(command)], cwd=ROOT)
    fault_events = []
    try:
        if scenario == "B" and stage in ("v1-db", "v2-db-fixed"):
            for _ in range(4 if smoke else 301):
                if process.poll() is not None:
                    raise RuntimeError("k6 ended before the contention snapshot")
                time.sleep(1)
            moment = "hold-contention" if stage == "v1-db" else "throughput-ceiling"
            subprocess.run([str(ROOT / "load-test/snapshot.sh"), stage, f"{run_id}-{moment}"],
                           cwd=ROOT, env=env, check=True)
            fault_events.append({"at": datetime.now(timezone.utc).isoformat(), "event": moment,
                                 "snapshot": f"../../../docs/evidence/{stage}/{run_id}-{moment}.json"})
        if fault:
            fault_events.extend(inject(hosts, key, env, stage, run_id, fault, smoke))
        status = process.wait()
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
    if jfr_name:
        jfr_save(hosts, key, stage.split("/", 1)[0], jfr_name)
    if fault_events:
        (directory / "fault-events.json").write_text(json.dumps(fault_events, indent=2) + "\n")
    subprocess.run(["scp", "-q", "-i", str(key),
                    f"ec2-user@{public(hosts, 'lg')}:{REMOTE_ROOT}/results/{stage}/{run_id}/summary.json",
                    str(directory / "summary.json")], cwd=ROOT, check=True)
    time.sleep(5)
    ended = time.time()
    subprocess.run([str(ROOT / "load-test/capture.sh"), stage, run_id, str(started), str(ended)],
                   cwd=ROOT, env=env, check=True)
    subprocess.run([str(ROOT / "load-test/report.sh"), stage, run_id, profile],
                   cwd=ROOT, env=env, check=True)
    subprocess.run([str(ROOT / "load-test/snapshot.sh"), stage, f"{run_id}-after"],
                   cwd=ROOT, env=env, check=True)
    if stage == "v1-db" and scenario == "A":
        subprocess.run([str(ROOT / "load-test/snapshot.sh"), stage, f"{run_id}-app-closed"],
                       cwd=ROOT, env=env, check=True)
        fault_events.append({"at": datetime.now(timezone.utc).isoformat(), "event": "app-closed",
                             "snapshot": f"../../../docs/evidence/{stage}/{run_id}-app-closed.json"})
    if stage == "v2-db-fixed" and scenario == "A":
        subprocess.run([str(ROOT / "load-test/snapshot.sh"), stage, f"{run_id}-v2-completion"],
                       cwd=ROOT, env=env, check=True)
        fault_events.append({"at": datetime.now(timezone.utc).isoformat(), "event": "v2-completion",
                             "snapshot": f"../../../docs/evidence/{stage}/{run_id}-v2-completion.json"})
    if stage == "v2-db-fixed" and scenario == "B":
        subprocess.run([str(ROOT / "load-test/snapshot.sh"), stage, f"{run_id}-v2-after-locks"],
                       cwd=ROOT, env=env, check=True)
        fault_events.append({"at": datetime.now(timezone.utc).isoformat(), "event": "v2-after-locks",
                             "snapshot": f"../../../docs/evidence/{stage}/{run_id}-v2-after-locks.json"})
    verify_run(stage, run_id)
    verify_seat_uniqueness(hosts, key)
    return {"at": datetime.fromtimestamp(started, timezone.utc).isoformat(),
            "endedAt": datetime.fromtimestamp(ended, timezone.utc).isoformat(),
            "event": f"scenario-{scenario}", "run": run_id, "exitCode": status}, fault_events


def run_remote(profile, hosts_file, key, smoke, skip_a=False):
    if not key.is_file():
        raise RuntimeError("SSH key is missing")
    identity = subprocess.check_output(["aws", "sts", "get-caller-identity", "--profile", "movie-loadtest",
                                        "--query", "Account", "--output", "text"], text=True).strip()
    if identity != "976071095824":
        raise RuntimeError("AWS account mismatch; remote measurement stopped")
    if not hosts_file.is_file() and hosts_file == ROOT / "infra/aws/hosts.env":
        subprocess.run([str(ROOT / "infra/aws/render-hosts-env.sh")], cwd=ROOT, check=True)
    if not hosts_file.is_file():
        raise RuntimeError("hosts.env is missing")
    hosts = read_env(hosts_file)
    tag = stage_settings(profile)["STAGE_CODE_TAG"]
    subprocess.run(["python3", str(ROOT / "infra/stages/ensure-local-env.py")],
                   cwd=ROOT, check=True)
    subprocess.run(["python3", str(ROOT / "infra/stages/measurement-secrets.py"), "create"],
                   cwd=ROOT, check=True)
    build_tag(tag, int(profile[1:]))
    output = ROOT / ".local/aws" / tag
    output.mkdir(parents=True, exist_ok=True)
    deploy(hosts_file, hosts, key, profile, output)
    grafana = read_env(Path.home() / ".config/movie-ticketing/loadtest-grafana.env")
    env = {**os.environ, "MEASUREMENT_HOSTS_ENV": str(hosts_file), "MEASUREMENT_SSH_KEY": str(key),
           "GRAFANA_URL": "http://127.0.0.1:13000", "PROMETHEUS_URL": "http://127.0.0.1:19090",
           "MOCK_PG_URL": "http://127.0.0.1:18082", "RESERVATION_API_URL": "http://127.0.0.1:18081",
           "GRAFANA_PASSWORD": grafana["GRAFANA_ADMIN_PASSWORD"]}
    tunnels = [tunnel(hosts, key, "mon", ((13000, 3000), (19090, 9090))),
               tunnel(hosts, key, "app-reservation", ((18081, 8081), (18082, 8082)))]
    time.sleep(1)
    if any(process.poll() is not None for process in tunnels):
        for process in tunnels:
            if process.poll() is None:
                process.terminate()
        raise RuntimeError("Measurement SSH port forwarding failed")
    batch = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    directory = ROOT / "load-test/results" / tag
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "batch.json").write_text(json.dumps({"batch": batch, "tag": tag,
        "profile": profile, "smoke": smoke, "skipA": skip_a,
        "startedAt": datetime.now(timezone.utc).isoformat()}, indent=2) + "\n")
    events = []
    try:
        scenarios = (("B", 1),) if skip_a else (("A", 3), ("B", 1))
        for scenario, count in scenarios:
            for index in range(1, count + 1):
                run_id = f"{batch}-{scenario.lower()}{index}"
                reset(hosts, key)
                time.sleep(1 if smoke else 120)
                jfr_name = f"{batch}-vt-on" if int(profile[1:]) in (2, 4) and scenario == "B" else None
                event, faults = measure(hosts, key, env, tag, profile, run_id, scenario, smoke,
                                        jfr_name=jfr_name)
                events.append(event)
                events.extend(faults)
        fault_plan = {3: [("worker-kill", "A")],
                      4: [("worker-kill", "A"), ("mysql-stop", "A")],
                      5: [("waiting-stop", "B")],
                      6: [("redis-kill", "A")]}.get(int(profile[1:]), [])
        for fault, scenario in fault_plan:
            run_id = f"{batch}-{fault}"
            reset(hosts, key)
            time.sleep(1 if smoke else 120)
            event, fault_events = measure(hosts, key, env, tag, profile, run_id, scenario, smoke, fault=fault)
            event["event"] = f"fault-{fault}"
            events.append(event)
            events.extend(fault_events)
            if fault == "redis-kill":
                with urlopen(env["RESERVATION_API_URL"] + "/admin/reconcile", timeout=30) as response:
                    response.read()
                moment = f"{run_id}-final-balance"
                subprocess.run([str(ROOT / "load-test/snapshot.sh"), tag, moment],
                               cwd=ROOT, env=env, check=True)
                verify_payment_balance(tag, moment)
                events.append({"at": datetime.now(timezone.utc).isoformat(), "event": "final-balance",
                               "snapshot": f"../../../docs/evidence/{tag}/{moment}.json"})
        if int(profile[1:]) in (2, 4):
            deploy(hosts_file, hosts, key, profile, output, virtual_off=True)
            reset(hosts, key)
            time.sleep(1 if smoke else 120)
            run_id = f"{batch}-b1"
            event, _ = measure(hosts, key, env, f"{tag}/vt-off", profile, run_id, "B", smoke,
                               jfr_name=f"{batch}-vt-off")
            event["event"] = "vt-off-scenario-B"
            event["run"] = f"vt-off/{run_id}"
            events.append(event)
        if profile == "v6":
            deploy(hosts_file, hosts, key, "v6-always", output)
            reset(hosts, key)
            time.sleep(1 if smoke else 120)
            run_id = f"{batch}-b1"
            event, _ = measure(hosts, key, env, f"{tag}/always", "v6-always", run_id, "B", smoke)
            event["event"] = "appendfsync-always-scenario-B"
            event["run"] = f"always/{run_id}"
            events.append(event)
        (directory / "events.json").write_text(json.dumps(events, indent=2) + "\n")
        subprocess.run([str(ROOT / "load-test/report.sh"), "--all"], cwd=ROOT, check=True)
        verify_artifact_secrets(tag, batch)
    finally:
        for process in tunnels:
            process.terminate()
            process.wait(timeout=5)
