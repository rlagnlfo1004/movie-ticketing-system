#!/usr/bin/env python3
"""Inject a single remote fault while k6 is running."""
from datetime import datetime, timezone
import json
import os
import subprocess
import time
from urllib.request import urlopen

from run_stage import ROOT


def event(name, stage=None, run_id=None):
    item = {"at": datetime.now(timezone.utc).isoformat(), "event": name}
    if stage and run_id:
        item["snapshot"] = f"../../../docs/evidence/{stage}/{run_id}-{name}.json"
    return item


def snapshot(env, stage, run_id, name, mysql_down=False):
    context = {**env, "MYSQL_UNAVAILABLE": "1"} if mysql_down else env
    subprocess.run([str(ROOT / "load-test/snapshot.sh"), stage, f"{run_id}-{name}"],
                   cwd=ROOT, env=context, check=True)
    return event(name, stage, run_id)


def wait_stream_drain(env, stage, run_id, moment):
    for _ in range(30):
        recorded = snapshot(env, stage, run_id, moment)
        path = ROOT / "docs/evidence" / stage / f"{run_id}-{moment}.json"
        state = json.loads(path.read_text())
        groups = state.get("streamGroups") or []
        lag = groups[0].get("lag") if groups else None
        pending = (state.get("streamPending") or [None])[0]
        if state.get("mysqlAvailable") and not state.get("missingMysqlSeats") and lag == 0 and pending == 0:
            return recorded
        time.sleep(2)
    raise RuntimeError(f"Remote Stream did not drain after {moment}")


def inject(hosts, key, env, stage, run_id, kind, smoke):
    from remote_stage import compose
    if kind not in ("worker-kill", "mysql-stop", "waiting-stop", "redis-kill"):
        raise ValueError(f"Unknown remote fault: {kind}")
    events = []
    moments = {
        "worker-kill": ("worker-before-kill", "worker-after-kill", "worker-after-restart")
            if stage.startswith("v3") else ("stream-before-kill", "stream-after-kill", "stream-after-recovery"),
        "mysql-stop": ("mysql-before-outage", "mysql-down", "mysql-recovered"),
        "waiting-stop": ("waiting-down-before", "waiting-down-after", "waiting-down-recovered"),
        "redis-kill": ("redis-before-kill", "redis-after-restart", "redis-after-reconcile"),
    }[kind]
    time.sleep(2 if smoke else 5)
    if kind == "worker-kill":
        events.append({"at": datetime.now(timezone.utc).isoformat(), "event": "worker initial state",
                       "snapshot": f"../../../docs/evidence/{stage}/{run_id}-before.json"})
        compose(hosts, key, "app-reservation", "kill", "-s", "SIGKILL", "reservation-worker")
        events.append(event("reservation-worker SIGKILL"))
        events.append(snapshot(env, stage, run_id, moments[1]))
        outage_state = None
        if not smoke:
            time.sleep(30)
            outage_moment = "worker-during-outage" if stage.startswith("v3") else "stream-during-outage"
            events.append(snapshot(env, stage, run_id, outage_moment))
            outage_state = json.loads((ROOT / "docs/evidence" / stage / f"{run_id}-{outage_moment}.json").read_text())
        compose(hosts, key, "app-reservation", "up", "-d", "--no-deps", "reservation-worker")
        events.append(event("reservation-worker restarted"))
        from remote_stage import wait_healthy
        wait_healthy(hosts, key, "app-reservation", "reservation-worker")
        if outage_state and stage.startswith("v4"):
            groups = outage_state.get("streamGroups") or []
            if not any((group.get("pending") or 0) > 0 or (group.get("lag") or 0) > 0 for group in groups):
                raise RuntimeError("No Stream backlog while worker was down")
    elif kind == "mysql-stop":
        events.append(snapshot(env, stage, run_id, moments[0]))
        compose(hosts, key, "mysql", "stop", "mysql")
        events.append(event("MySQL stopped"))
        events.append(snapshot(env, stage, run_id, moments[1], mysql_down=True))
        time.sleep(1 if smoke else 30)
        compose(hosts, key, "mysql", "start", "mysql")
        events.append(event("MySQL started"))
        from remote_stage import wait_healthy
        wait_healthy(hosts, key, "mysql", "mysql")
    elif kind == "waiting-stop":
        events.append(snapshot(env, stage, run_id, moments[0]))
        compose(hosts, key, "app-waiting", "stop", "waiting-api-1", "waiting-api-2", "waiting-api-3")
        events.append(event("all waiting-api stopped"))
        events.append(snapshot(env, stage, run_id, moments[1]))
        time.sleep(1 if smoke else 30)
        compose(hosts, key, "app-waiting", "start", "waiting-api-1", "waiting-api-2", "waiting-api-3")
        events.append(event("all waiting-api started"))
        from remote_stage import wait_healthy
        for service in ("waiting-api-1", "waiting-api-2", "waiting-api-3"):
            wait_healthy(hosts, key, "app-waiting", service)
    else:
        events.append(snapshot(env, stage, run_id, moments[0]))
        from remote_stage import remote
        remote(hosts, key, "redis", "docker", "compose", "-f", "/home/ec2-user/movie-ticketing/compose.json",
               "exec", "-T", "redis", "sh", "-c", "kill -9 1", check=False)
        events.append(event("Redis SIGKILL"))
        compose(hosts, key, "redis", "up", "-d", "--no-deps", "redis")
        events.append(event("Redis restarted"))
        for _ in range(30):
            status = compose(hosts, key, "redis", "exec", "-T", "redis", "redis-cli", "PING",
                             check=False, capture=True)
            if status.stdout.strip() == b"PONG":
                break
            time.sleep(1)
        else:
            raise RuntimeError("Redis did not recover")
        events.append(snapshot(env, stage, run_id, moments[1]))
        with urlopen(env["RESERVATION_API_URL"] + "/admin/reconcile", timeout=30) as response:
            response.read()
        events.append(event("reconciliation called"))
    if kind == "mysql-stop" or kind == "worker-kill" and stage.startswith("v4"):
        events.append(wait_stream_drain(env, stage, run_id, moments[2]))
    else:
        events.append(snapshot(env, stage, run_id, moments[2]))
    return events
