#!/usr/bin/env python3
"""Inject a single remote fault while k6 is running."""
from datetime import datetime, timezone
import json
import os
import subprocess
import time
from urllib.request import Request, urlopen

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


def sentinel_master(hosts, key):
    from remote_stage import compose
    reports = []
    for role, service in (("edge", "sentinel-1"), ("app-waiting", "sentinel-2"),
                          ("app-reservation", "sentinel-3")):
        result = compose(hosts, key, role, "exec", "-T", service, "redis-cli", "-p", "26379",
                         "--raw", "SENTINEL", "master", "movie-primary", check=False, capture=True)
        if result.returncode:
            continue
        fields = result.stdout.decode(errors="replace").splitlines()
        info = dict(zip(fields[::2], fields[1::2]))
        if info.get("flags") == "master":
            reports.append((info.get("ip"), info.get("config-epoch")))
    for value in reports:
        if reports.count(value) >= 2:
            return value
    return None


def inject(hosts, key, env, stage, run_id, kind, smoke):
    from remote_stage import compose
    if kind not in ("worker-kill", "mysql-stop", "waiting-stop", "redis-kill", "sentinel-failover"):
        raise ValueError(f"Unknown remote fault: {kind}")
    events = []
    moments = {
        "worker-kill": ("worker-before-kill", "worker-after-kill", "worker-after-restart")
            if stage.startswith("v3") else ("stream-before-kill", "stream-after-kill", "stream-after-recovery"),
        "mysql-stop": ("mysql-before-outage", "mysql-down", "mysql-recovered"),
        "waiting-stop": ("waiting-down-before", "waiting-down-after", "waiting-down-recovered"),
        "redis-kill": ("redis-before-kill", "redis-after-restart", "redis-after-reconcile"),
        "sentinel-failover": ("sentinel-before-kill", "sentinel-after-promotion",
                              "sentinel-after-reconcile"),
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
    elif kind == "sentinel-failover":
        from remote_stage import remote
        before_master = sentinel_master(hosts, key)
        if before_master is None or before_master[0] != hosts["REDIS_PRIVATE_IP"]:
            raise RuntimeError("V7 initial primary or Sentinel quorum is not ready")
        if not smoke:
            for _ in range(90):
                result = compose(hosts, key, "redis", "exec", "-T", "redis", "redis-cli",
                                 "SCARD", "seat:{1}:reserved", check=False, capture=True)
                if result.returncode == 0 and int(result.stdout.strip() or b"0") >= 20:
                    break
                time.sleep(1)
            else:
                raise RuntimeError("No confirmed seats before V7 primary fault")
        events.append(snapshot(env, stage, run_id, moments[0]))
        failed_at = time.time()
        compose(hosts, key, "redis", "kill", "-s", "SIGKILL", "redis")
        events.append(event("Redis primary SIGKILL"))
        promoted = None
        for _ in range(90):
            current = sentinel_master(hosts, key)
            if current and current[0] != before_master[0] and int(current[1]) > int(before_master[1]):
                promoted = current
                break
            time.sleep(1)
        if promoted is None:
            raise RuntimeError("Sentinel did not promote a replica after primary SIGKILL")
        new_role = next((role for role in ("redis-replica-1", "redis-replica-2")
                         if hosts[role.upper().replace("-", "_") + "_PRIVATE_IP"] == promoted[0]), None)
        if new_role is None:
            raise RuntimeError("Sentinel promoted an unknown Redis host")
        env["MEASUREMENT_REDIS_ROLE"] = new_role.upper().replace("-", "_")
        events.append({"at": datetime.now(timezone.utc).isoformat(), "event": "Sentinel promoted replica",
                       "role": new_role, "configEpoch": int(promoted[1]),
                       "promotionSeconds": round(time.time() - failed_at, 3)})
        time.sleep(1 if smoke else 30)
        events.append(snapshot(env, stage, run_id, moments[1]))
        with urlopen(Request(env["RESERVATION_API_URL"] + "/admin/reconcile", data=b"",
                             method="POST"), timeout=45) as response:
            response.read()
        events.append(event("post-failover reconciliation completed"))
        compose(hosts, key, "redis", "up", "-d", "--no-deps", "redis")
        for _ in range(45):
            role = compose(hosts, key, "redis", "exec", "-T", "redis", "redis-cli", "INFO",
                           "replication", check=False, capture=True)
            if b"role:slave" in role.stdout:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Former primary did not rejoin as a replica")
        rejected = compose(hosts, key, "redis", "exec", "-T", "redis", "redis-cli", "SET",
                           "v7:former-primary-probe", "blocked", check=False, capture=True)
        if b"READONLY" not in rejected.stdout and b"READONLY" not in rejected.stderr:
            raise RuntimeError("Former primary accepted a write after failover")
        events.append(event("former primary rejected write"))
    else:
        if not smoke:
            for _ in range(90):
                result = compose(hosts, key, "redis", "exec", "-T", "redis", "redis-cli",
                                 "SCARD", "seat:{1}:reserved", check=False, capture=True)
                if result.returncode == 0 and int(result.stdout.strip() or b"0") >= 20:
                    break
                time.sleep(1)
            else:
                raise RuntimeError("No confirmed seats before Redis fault injection")
        events.append(snapshot(env, stage, run_id, moments[0]))
        if not smoke:
            before = json.loads((ROOT / "docs/evidence" / stage / f"{run_id}-{moments[0]}.json").read_text())
            if not before.get("redisReservedSeats"):
                raise RuntimeError("Redis fault snapshot has no confirmed seats")
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
        with urlopen(Request(env["RESERVATION_API_URL"] + "/admin/reconcile", data=b"",
                             method="POST"), timeout=30) as response:
            response.read()
        events.append(event("reconciliation called"))
    if kind == "mysql-stop" or kind == "worker-kill" and stage.startswith("v4"):
        events.append(wait_stream_drain(env, stage, run_id, moments[2]))
    else:
        events.append(snapshot(env, stage, run_id, moments[2]))
    return events
