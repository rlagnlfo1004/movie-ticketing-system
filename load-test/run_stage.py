#!/usr/bin/env python3
"""Run each measurement from its immutable stage tag in a local Compose stack."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.request import urlopen
from local_env import get as local_env

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ("redis", "mysql", "mock-pg", "waiting-api-1", "waiting-api-2",
            "waiting-api-3", "reservation-api", "nginx", "prometheus", "grafana",
            "renderer", "node-exporter", "mysqld-exporter", "redis-exporter", "nginx-exporter")


def run(*cmd, env=None, stdout=None):
    subprocess.run(cmd, cwd=ROOT, env=env, stdout=stdout, check=True)


def stage_settings(profile):
    path = ROOT / "infra/stages" / f"{profile}.env"
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if line and not line.startswith("#") and "=" in line)


def build_tag(tag, stage):
    with tempfile.TemporaryDirectory(prefix="movie-stage-") as temporary:
        checkout = Path(temporary) / "source"
        run("git", "worktree", "add", "--detach", str(checkout), tag)
        try:
            modules = ["waiting-api", "reservation-api"]
            if stage >= 3:
                modules.append("reservation-worker")
            subprocess.run([str(checkout / "gradlew"), *(f":{module}:bootJar" for module in modules)],
                           cwd=checkout, check=True)
            for module in modules:
                source = checkout / module / "build/libs" / f"{module}-0.0.1-SNAPSHOT.jar"
                destination = ROOT / module / "build/libs" / source.name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)
            fixture = ROOT / ".local/stage"
            fixture.mkdir(parents=True, exist_ok=True)
            (fixture / "mock-pg.mjs").write_bytes(subprocess.check_output(
                ["git", "show", f"{tag}:load-test/mock-pg.mjs"], cwd=ROOT))
            (fixture / "tag.txt").write_text(tag + "\n")
            (fixture / "mock-pg.Dockerfile").write_text(
                "FROM node:22-alpine\nWORKDIR /app\nCOPY .local/stage/mock-pg.mjs /app/mock-pg.mjs\n"
                "CMD [\"node\", \"/app/mock-pg.mjs\"]\n")
            (fixture / "compose.json").write_text(json.dumps({"services": {"mock-pg": {
                "build": {"context": str(ROOT), "dockerfile": ".local/stage/mock-pg.Dockerfile"}}}}))
        finally:
            run("git", "worktree", "remove", "--force", str(checkout))


def ready(url, seconds=120):
    import urllib.request
    for _ in range(seconds):
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError(f"Service did not become ready: {url}")


def healthy(service, seconds=120):
    for _ in range(seconds):
        container = subprocess.check_output(["docker", "compose", "ps", "-q", service],
                                            cwd=ROOT, text=True).strip()
        if container:
            state = subprocess.check_output(["docker", "inspect", "--format", "{{.State.Health.Status}}", container],
                                            text=True).strip()
            if state == "healthy":
                return
        time.sleep(1)
    raise RuntimeError(f"Service did not become healthy: {service}")


def reset_stack(stage, env):
    compose = ("docker", "compose", "-f", str(ROOT / "docker-compose.yml"),
               "-f", str(ROOT / ".local/stage/compose.json"))
    run(*compose, "down", "--volumes", "--remove-orphans", env=env)
    run(*compose, "up", "-d", "--build", "--no-deps", *SERVICES[:3], env=env)
    ready("http://localhost:8082/health")
    healthy("mysql")
    for _ in range(30):
        flushed = subprocess.run([*compose, "exec", "-T", "redis", "redis-cli", "FLUSHALL"],
                                 cwd=ROOT, env=env, capture_output=True, text=True)
        if flushed.returncode == 0 and flushed.stdout.strip() == "OK":
            break
        time.sleep(1)
    else:
        raise RuntimeError("Redis FLUSHALL failed")
    for service in ("waiting-api-1", "waiting-api-2", "waiting-api-3"):
        run(*compose, "up", "-d", "--no-deps", service, env=env)
    if stage >= 3:
        run(*compose, "up", "-d", "--no-deps", "reservation-worker", env=env)
    run(*compose, "up", "-d", "--no-deps", "reservation-api", env=env)
    ready("http://localhost:8081/actuator/health/readiness")
    run(*compose, "up", "-d", "--no-deps", *SERVICES[7:], env=env)
    ready("http://localhost:3000/api/health")
    ready("http://localhost:9090/-/ready")
    run(*compose, "exec", "-T", "nginx", "nginx", "-s", "reload", env=env)


def snapshot(stage, moment, events):
    run(str(ROOT / "load-test/snapshot.sh"), stage, moment)
    events.append({"at": datetime.now(timezone.utc).isoformat(), "event": moment,
                   "snapshot": f"../../../docs/evidence/{stage}/{moment}.json"})


def verify_run(stage, run_id):
    directory = ROOT / "load-test/results" / stage / run_id
    expected = [directory / "summary.json", directory / "dashboard.png"]
    panels = list((directory / "panels").glob("*.png"))
    metrics = list((directory / "prometheus").glob("*.json"))
    count = len(json.loads((ROOT / "observability/grafana/provisioning/dashboards/json/seat.json").read_text())["panels"])
    if any(not path.exists() for path in expected) or len(panels) < count or len(metrics) < count:
        raise RuntimeError(f"Incomplete capture: {directory}")
    if not (directory / "dashboard.png").read_bytes().startswith(b"\x89PNG"):
        raise RuntimeError(f"Invalid dashboard PNG: {directory}")


def verify_seat_uniqueness():
    secret = local_env("SEAT_DB_ROOT_PASSWORD")
    sql = ("SELECT COUNT(*) FROM (SELECT screening_id,seat_no FROM reservation_seats "
           "GROUP BY screening_id,seat_no HAVING COUNT(*)>1) duplicates")
    result = subprocess.run(["docker", "compose", "exec", "-T", "-e", f"MYSQL_PWD={secret}",
                             "mysql", "mysql", "-uroot", "-N", "-B", "movie_seats", "-e", sql],
                            cwd=ROOT, capture_output=True, text=True)
    if result.returncode or result.stdout.strip() != "0":
        raise RuntimeError("Reservation seats contain duplicates or MySQL verification failed")


def verify_payment_balance(stage, moment):
    path = ROOT / "docs/evidence" / stage / f"{moment}.json"
    state = json.loads(path.read_text())
    approved = set(state["approvedPgPayments"])
    confirmed = set(state["confirmedPgPayments"] or [])
    cancelled = set(state["cancelledPgPayments"])
    if approved != confirmed | cancelled or confirmed & cancelled:
        raise RuntimeError(f"PG payment balance differs: {path}")


def verify_artifact_secrets(stage, batch=None):
    def values(path):
        if not path.exists():
            return []
        return [line.split("=", 1)[1].encode() for line in path.read_text().splitlines()
                if "=" in line and not line.startswith("#") and
                any(word in line.split("=", 1)[0].upper() for word in ("SECRET", "PASSWORD", "KEY")) and
                len(line.split("=", 1)[1]) >= 8]
    secrets = set(values(ROOT / ".env") +
                  values(Path.home() / ".config/movie-ticketing/portone.env") +
                  values(Path.home() / ".config/movie-ticketing/loadtest-grafana.env"))
    global_report = ROOT / "load-test/results/README.md"
    if secrets and global_report.exists():
        contents = global_report.read_bytes()
        if any(secret in contents for secret in secrets):
            raise RuntimeError(f"Secret appeared in evidence: {global_report}")
    for directory in (ROOT / "load-test/results" / stage, ROOT / "docs/evidence" / stage):
        if not directory.exists():
            continue
        for path in directory.rglob("*"):
            if batch and not any(part.startswith(batch) for part in path.parts):
                continue
            if path.is_file() and secrets:
                data = path.read_bytes()
                if any(secret in data for secret in secrets):
                    raise RuntimeError(f"Secret appeared in evidence: {path}")


def jfr_start():
    run("docker", "compose", "exec", "-T", "reservation-api", "jcmd", "1", "JFR.start",
        "name=measurement", "settings=profile")


def jfr_save(stage, name):
    destination = ROOT / "docs/evidence" / stage
    destination.mkdir(parents=True, exist_ok=True)
    filename = f"{name}.jfr"
    raw_filename = f"{name}-raw.jfr"
    run("docker", "compose", "exec", "-T", "reservation-api", "jcmd", "1", "JFR.dump",
        "name=measurement", f"filename=/app/{raw_filename}")
    run("docker", "compose", "exec", "-T", "reservation-api", "jcmd", "1", "JFR.stop",
        "name=measurement")
    try:
        run("docker", "compose", "exec", "-T", "reservation-api", "jfr", "scrub", "--include-events",
            "jdk.VirtualThreadPinned", f"/app/{raw_filename}", f"/app/{filename}")
        shutil.copy2(ROOT / "reservation-api/build/libs" / filename, destination / filename)
        with (destination / f"{name}-pinning.txt").open("w") as output:
            run("docker", "compose", "exec", "-T", "reservation-api", "jfr", "print", "--events",
                "jdk.VirtualThreadPinned", f"/app/{filename}", stdout=output)
        pinning = destination / f"{name}-pinning.txt"
        if pinning.stat().st_size == 0:
            pinning.write_text("No jdk.VirtualThreadPinned events in this recording.\n")
    finally:
        (ROOT / "reservation-api/build/libs" / raw_filename).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=[f"v{n}" for n in range(1, 8)])
    parser.add_argument("--smoke", action="store_true", help="Short local plumbing check; never a performance result")
    parser.add_argument("--skip-a", action="store_true", help="Run B and fault cases without three baseline A runs")
    parser.add_argument("--fault-only", choices=("worker-kill", "mysql-stop", "waiting-stop", "redis-kill",
                                                "sentinel-failover"),
                        help="Repeat one fault in the current remote batch")
    parser.add_argument("--hosts-env", help="Terraform hosts.env; deploy to the seven AWS hosts")
    parser.add_argument("--ssh-key", default=str(Path.home() / ".ssh/id_ed25519"))
    args = parser.parse_args()
    if args.hosts_env:
        from remote_stage import run_remote
        run_remote(args.stage, Path(args.hosts_env).resolve(), Path(args.ssh_key).expanduser().resolve(),
                   args.smoke, args.skip_a, args.fault_only)
        return
    if args.stage == "v7":
        parser.error("V7 local verification uses docker-compose.v7.yml and load-test/v7-local-smoke.py")
    if args.skip_a or args.fault_only:
        parser.error("--skip-a and --fault-only require --hosts-env")
    settings = stage_settings(args.stage)
    stage_number = int(args.stage[1:])
    stage = settings["STAGE_CODE_TAG"]
    run("git", "rev-parse", "--verify", f"refs/tags/{stage}")
    run("python3", str(ROOT / "infra/stages/ensure-local-env.py"))
    env = {**os.environ, **settings, "STAGE": stage, "STAGE_PROFILE": args.stage,
           "SMOKE": "1" if args.smoke else "0", "STAGE_SECONDS": "1" if args.smoke else "60",
           "SKIP_SETUP": "1"}
    batch = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    directory = ROOT / "load-test/results" / stage
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "batch.json").write_text(json.dumps({"batch": batch, "tag": stage,
        "profile": args.stage, "smoke": args.smoke, "startedAt": datetime.now(timezone.utc).isoformat()}, indent=2) + "\n")
    events = []
    build_tag(stage, stage_number)
    for scenario, count in (("A", 3), ("B", 1)):
        for index in range(1, count + 1):
            run_id = f"{batch}-{scenario.lower()}{index}"
            reset_stack(stage_number, env)
            time.sleep(1 if args.smoke else 120)
            snapshot(stage, f"{run_id}-before", events)
            if stage_number in (2, 4) and scenario == "B":
                jfr_start()
            started = datetime.now(timezone.utc).isoformat()
            run_env = {**env, "SCENARIO": scenario, "RUN_ID": run_id}
            if scenario == "B" and stage_number in (1, 2):
                run_env.update({"SNAPSHOT_MOMENT": "hold-contention" if stage_number == 1 else "throughput-ceiling",
                                "SNAPSHOT_DELAY_SECONDS": "4" if args.smoke else "301"})
            result = subprocess.run([str(ROOT / "load-test/run.sh")], cwd=ROOT,
                                    env=run_env, check=False)
            events.append({"at": started, "endedAt": datetime.now(timezone.utc).isoformat(),
                           "event": f"scenario-{scenario}", "run": run_id, "exitCode": result.returncode})
            snapshot(stage, f"{run_id}-after", events)
            if scenario == "B" and stage_number in (1, 2):
                name = run_env["SNAPSHOT_MOMENT"]
                state = json.loads((ROOT / "docs/evidence" / stage / f"{run_id}-{name}.json").read_text())
                events.append({"at": state["capturedAt"], "event": name,
                    "snapshot": f"../../../docs/evidence/{stage}/{run_id}-{name}.json"})
            if scenario == "A" and stage_number == 1:
                snapshot(stage, f"{run_id}-app-closed", events)
            if scenario == "A" and stage_number == 2:
                snapshot(stage, f"{run_id}-v2-completion", events)
            if scenario == "B" and stage_number == 2:
                snapshot(stage, f"{run_id}-v2-after-locks", events)
            verify_run(stage, run_id)
            verify_seat_uniqueness()
            if stage_number in (2, 4) and scenario == "B":
                jfr_save(stage, f"{batch}-vt-on")
    fault_plan = {3: [("worker-kill", "A")],
                  4: [("worker-kill", "A"), ("mysql-stop", "A")],
                  5: [("waiting-stop", "B")],
                  6: [("redis-kill", "A")]}.get(stage_number, [])
    for fault, scenario in fault_plan:
        run_id = f"{batch}-{fault}"
        reset_stack(stage_number, env)
        time.sleep(1 if args.smoke else 120)
        started = datetime.now(timezone.utc).isoformat()
        result = subprocess.run([str(ROOT / "load-test/run.sh")], cwd=ROOT, check=False,
            env={**env, "SCENARIO": scenario, "RUN_ID": run_id, "FAULT_KIND": fault,
                 "FAULT_DELAY_SECONDS": "2" if args.smoke else "5",
                 "FAULT_OUTAGE_SECONDS": "1" if args.smoke else "30"})
        events.append({"at": started, "endedAt": datetime.now(timezone.utc).isoformat(),
                       "event": f"fault-{fault}", "run": run_id, "exitCode": result.returncode})
        verify_run(stage, run_id)
        verify_seat_uniqueness()
        details = json.loads((directory / run_id / "fault-events.json").read_text())
        status = json.loads((directory / run_id / "fault-status.json").read_text())
        if not status["completed"]:
            raise RuntimeError(f"Fault injection did not recover: {fault}")
        events.extend(details)
        if fault == "redis-kill":
            with urlopen("http://localhost:8081/admin/reconcile", timeout=30) as response:
                response.read()
            moment = f"{run_id}-final-balance"
            snapshot(stage, moment, events)
            verify_payment_balance(stage, moment)
    if stage_number in (2, 4):
        off_env = {**env, "SPRING_THREADS_VIRTUAL_ENABLED": "false",
                   "STAGE": f"{stage}/vt-off"}
        run_id = f"{batch}-b1"
        reset_stack(stage_number, off_env)
        time.sleep(1 if args.smoke else 120)
        jfr_start()
        started = datetime.now(timezone.utc).isoformat()
        result = subprocess.run([str(ROOT / "load-test/run.sh")], cwd=ROOT, check=False,
            env={**off_env, "SCENARIO": "B", "RUN_ID": run_id})
        jfr_save(stage, f"{batch}-vt-off")
        events.append({"at": started, "endedAt": datetime.now(timezone.utc).isoformat(),
                       "event": "vt-off-scenario-B", "run": f"vt-off/{run_id}", "exitCode": result.returncode})
        verify_run(f"{stage}/vt-off", run_id)
        verify_seat_uniqueness()
    if stage_number == 6:
        always_env = {**env, **stage_settings("v6-always"),
                      "STAGE": f"{stage}/always", "STAGE_PROFILE": "v6-always"}
        run_id = f"{batch}-b1"
        reset_stack(stage_number, always_env)
        time.sleep(1 if args.smoke else 120)
        started = datetime.now(timezone.utc).isoformat()
        result = subprocess.run([str(ROOT / "load-test/run.sh")], cwd=ROOT, check=False,
            env={**always_env, "SCENARIO": "B", "RUN_ID": run_id})
        events.append({"at": started, "endedAt": datetime.now(timezone.utc).isoformat(),
                       "event": "appendfsync-always-scenario-B", "run": f"always/{run_id}",
                       "exitCode": result.returncode})
        verify_run(f"{stage}/always", run_id)
        verify_seat_uniqueness()
    (directory / "events.json").write_text(json.dumps(events, indent=2, ensure_ascii=False) + "\n")
    run(str(ROOT / "load-test/report.sh"), "--all")
    verify_artifact_secrets(stage, batch)
    print(ROOT / "load-test/results/README.md")


if __name__ == "__main__":
    main()
