#!/usr/bin/env python3
"""Inject an invalid Stream event and verify delivery four moves it to DLQ."""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

root = Path(__file__).resolve().parents[1]
stream = "stream:{1}:reservations"
dlq = stream + ":dlq"


def run(*args, env=None):
    return subprocess.run(args, cwd=root, env=env, capture_output=True, text=True, check=True).stdout.strip()


def redis_json(*args):
    return json.loads(run("docker", "compose", "exec", "-T", "redis", "redis-cli", "--json", *args))


fast = os.environ.copy()
fast["WORKER_RECLAIM_MIN_IDLE"] = "1s"
run("docker", "compose", "up", "-d", "reservation-worker", env=fast)
try:
    for _ in range(30):
        try:
            with urllib.request.urlopen("http://localhost:8084/actuator/health", timeout=1) as response:
                if response.status == 200:
                    break
        except Exception:
            time.sleep(.5)
    else:
        raise SystemExit("Worker did not start")
    before = redis_json("XLEN", dlq)
    run("docker", "compose", "exec", "-T", "redis", "redis-cli", "XADD", stream, "*", "event", "{invalid-json")
    for _ in range(70):
        after = redis_json("XLEN", dlq)
        pending = redis_json("XPENDING", stream, "workers")[0]
        if after > before and pending == 0:
            break
        time.sleep(.5)
    else:
        raise SystemExit("Invalid event did not reach DLQ")
    run(str(root / "load-test/snapshot.sh"), "v4-streams", "dlq-after-retries")
    print("V4 invalid event moved to DLQ after retry limit")
finally:
    run("docker", "compose", "up", "-d", "reservation-worker")
