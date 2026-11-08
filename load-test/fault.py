#!/usr/bin/env python3
"""Inject one measured fault and retain its exact UTC timing and state snapshots."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ("docker", "compose", "-f", str(ROOT / "docker-compose.yml"))


def record(events, name):
    events.append({"at": datetime.now(timezone.utc).isoformat(), "event": name})


def run(*args, env=None):
    subprocess.run(args, cwd=ROOT, env=env, check=True)


def snapshot(stage, run_id, moment, events, mysql_unavailable=False):
    name = f"{run_id}-{moment}"
    env = {**os.environ, "MYSQL_UNAVAILABLE": "1"} if mysql_unavailable else None
    run(str(ROOT / "load-test/snapshot.sh"), stage, name, env=env)
    record(events, moment)
    events[-1]["snapshot"] = f"../../../docs/evidence/{stage}/{name}.json"


def wait_healthy(service):
    for _ in range(120):
        container = subprocess.check_output([*COMPOSE, "ps", "-q", service], cwd=ROOT, text=True).strip()
        if container:
            status = subprocess.check_output(["docker", "inspect", "--format", "{{.State.Health.Status}}", container],
                                             text=True).strip()
            if status == "healthy":
                return
        time.sleep(1)
    raise RuntimeError(f"{service} did not recover")


def wait_stream_drain(stage, run_id, moment, events):
    name = f"{run_id}-{moment}"
    path = ROOT / "docs/evidence" / stage / f"{name}.json"
    for _ in range(90):
        run(str(ROOT / "load-test/snapshot.sh"), stage, name)
        state = json.loads(path.read_text())
        groups = state.get("streamGroups") or []
        lag = groups[0].get("lag") if groups else None
        pending = (state.get("streamPending") or [None])[0]
        if state.get("mysqlAvailable") and not state.get("missingMysqlSeats") and lag == 0 and pending == 0:
            record(events, moment)
            events[-1]["snapshot"] = f"../../../docs/evidence/{stage}/{name}.json"
            return
        time.sleep(1)
    raise RuntimeError(f"Stream did not drain after {moment}")


def main(stage, run_id, kind, delay, outage):
    if kind not in ("worker-kill", "mysql-stop", "waiting-stop", "redis-kill"):
        raise SystemExit(f"Unknown fault: {kind}")
    events = []
    moments = {
        "worker-kill": ("worker-before-kill", "worker-after-kill", "worker-after-restart")
            if stage.startswith("v3") else ("stream-before-kill", "stream-after-kill", "stream-after-recovery"),
        "mysql-stop": ("mysql-before-outage", "mysql-down", "mysql-recovered"),
        "waiting-stop": ("waiting-down-before", "waiting-down-after", "waiting-down-recovered"),
        "redis-kill": ("redis-before-kill", "redis-after-restart", "redis-after-reconcile"),
    }[kind]
    directory = ROOT / "load-test/results" / stage / run_id
    directory.mkdir(parents=True, exist_ok=True)
    completed = False
    try:
        time.sleep(float(delay))
        snapshot(stage, run_id, moments[0], events)
        if kind == "worker-kill":
            run(*COMPOSE, "kill", "-s", "SIGKILL", "reservation-worker")
            record(events, "reservation-worker SIGKILL")
            snapshot(stage, run_id, moments[1], events)
            run(*COMPOSE, "up", "-d", "--no-deps", "reservation-worker")
            record(events, "reservation-worker restarted")
            wait_healthy("reservation-worker")
        elif kind == "mysql-stop":
            run(*COMPOSE, "stop", "mysql")
            record(events, "MySQL stopped")
            snapshot(stage, run_id, moments[1], events, mysql_unavailable=True)
            time.sleep(float(outage))
            run(*COMPOSE, "start", "mysql")
            record(events, "MySQL started")
            wait_healthy("mysql")
        elif kind == "waiting-stop":
            run(*COMPOSE, "stop", "waiting-api-1", "waiting-api-2", "waiting-api-3")
            record(events, "all waiting-api stopped")
            snapshot(stage, run_id, moments[1], events)
            time.sleep(float(outage))
            run(*COMPOSE, "start", "waiting-api-1", "waiting-api-2", "waiting-api-3")
            record(events, "all waiting-api started")
            for service in ("waiting-api-1", "waiting-api-2", "waiting-api-3"):
                wait_healthy(service)
        else:
            subprocess.run([*COMPOSE, "exec", "-T", "redis", "sh", "-c", "kill -9 1"],
                           cwd=ROOT, check=False)
            record(events, "Redis SIGKILL")
            run(*COMPOSE, "up", "-d", "--no-deps", "redis")
            record(events, "Redis restarted")
            for _ in range(30):
                response = subprocess.run([*COMPOSE, "exec", "-T", "redis", "redis-cli", "PING"],
                                          cwd=ROOT, capture_output=True, text=True)
                if response.stdout.strip() == "PONG":
                    break
                time.sleep(1)
            snapshot(stage, run_id, moments[1], events)
            with urlopen("http://localhost:8081/admin/reconcile", timeout=30) as response:
                response.read()
            record(events, "reconciliation called")
        if kind == "mysql-stop" or kind == "worker-kill" and stage.startswith("v4"):
            wait_stream_drain(stage, run_id, moments[2], events)
        else:
            snapshot(stage, run_id, moments[2], events)
        completed = True
    finally:
        (directory / "fault-events.json").write_text(json.dumps(events, ensure_ascii=False, indent=2) + "\n")
        (directory / "fault-status.json").write_text(json.dumps({"completed": completed}) + "\n")


if __name__ == "__main__":
    if len(sys.argv) != 6:
        raise SystemExit("usage: fault.py <stage> <run> <kind> <delay-seconds> <outage-seconds>")
    main(*sys.argv[1:])
