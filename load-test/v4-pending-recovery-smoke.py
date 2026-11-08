#!/usr/bin/env python3
"""Kill a worker after XREADGROUP, then verify XAUTOCLAIM persists and ACKs it."""
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1]
base = "http://localhost:8000/api/v1"
stream = "stream:{1}:reservations"


def run(*args, env=None):
    return subprocess.run(args, cwd=root, env=env, capture_output=True, text=True, check=True).stdout


def redis_json(*args):
    return json.loads(run("docker", "compose", "exec", "-T", "redis", "redis-cli", "--json", *args))


def snapshot(moment):
    run(str(root / "load-test/snapshot.sh"), "v4-streams", moment)
    return json.loads((root / "docs/evidence/v4-streams" / f"{moment}.json").read_text())


def post(url, data, headers=None):
    request = urllib.request.Request(url, method="POST", data=json.dumps(data).encode(),
                                     headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


with urllib.request.urlopen(base + "/screenings/1/seats", timeout=10) as response:
    seat = next(item["seatNo"] for item in json.load(response)["seats"] if item["status"] == "AVAILABLE")

import os
paused_env = os.environ.copy()
paused_env["WORKER_TEST_WRITE_PAUSE"] = "5s"
run("docker", "compose", "up", "-d", "reservation-worker", env=paused_env)
for _ in range(30):
    try:
        with urllib.request.urlopen("http://localhost:8084/actuator/health", timeout=1) as response:
            if response.status == 200:
                break
    except Exception:
        time.sleep(.5)
else:
    raise SystemExit("Paused worker did not start")

token = f"recover-{uuid.uuid4()}"
run("docker", "compose", "exec", "-T", "redis", "redis-cli", "SET",
    f"waiting:screening:1:active:{token}", token, "EX", "600")
hold = post(base + "/screenings/1/holds", {"seats": [seat]},
            {"Queue-Token": token, "Idempotency-Key": f"recover-key-{uuid.uuid4()}"})
post(f"http://localhost:8082/test/payments/{hold['paymentId']}/approve",
     {"amount": 15000, "webhookUrl": "http://reservation-api:8081/api/v1/webhooks/portone"})
for _ in range(20):
    if redis_json("XPENDING", stream, "workers")[0] > 0:
        break
    time.sleep(.25)
else:
    raise SystemExit("Worker did not read into PEL")

snapshot("stream-before-kill")
run("docker", "compose", "kill", "-s", "SIGKILL", "reservation-worker")
after_kill = snapshot("stream-after-kill")
if after_kill["streamPending"][0] < 1:
    raise SystemExit("PEL was empty after SIGKILL")
run("docker", "compose", "up", "-d", "reservation-worker")
for _ in range(100):
    if redis_json("XPENDING", stream, "workers")[0] == 0:
        break
    time.sleep(.5)
else:
    raise SystemExit("XAUTOCLAIM did not drain PEL")
after = snapshot("stream-after-recovery")
if seat in after["missingMysqlSeats"] or after["streamPending"][0] != 0:
    raise SystemExit("Recovered event was not persisted and ACKed")
print("V4 XAUTOCLAIM recovered a SIGKILLed worker event")
