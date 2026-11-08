#!/usr/bin/env python3
"""Kill the Pub/Sub worker, confirm one seat, and show the event is not replayed."""
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1]
base = "http://localhost:8000/api/v1"


def run(*args):
    return subprocess.run(args, cwd=root, capture_output=True, text=True, check=True).stdout


def snapshot(moment):
    run(str(root / "load-test/snapshot.sh"), "v3-pubsub", moment)
    return json.loads((root / "docs/evidence/v3-pubsub" / f"{moment}.json").read_text())


def post(url, data, headers=None):
    request = urllib.request.Request(url, method="POST", data=json.dumps(data).encode(),
                                     headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


with urllib.request.urlopen(base + "/screenings/1/seats", timeout=10) as response:
    seat = next(value["seatNo"] for value in json.load(response)["seats"] if value["status"] == "AVAILABLE")

before = snapshot("worker-before-kill")
run("docker", "compose", "kill", "-s", "SIGKILL", "reservation-worker")
token = f"lost-{uuid.uuid4()}"
run("docker", "compose", "exec", "-T", "redis", "redis-cli", "SET",
    f"waiting:screening:1:active:{token}", token, "EX", "600")
hold = post(base + "/screenings/1/holds", {"seats": [seat]},
            {"Queue-Token": token, "Idempotency-Key": f"lost-key-{uuid.uuid4()}"})
post(f"http://localhost:8082/test/payments/{hold['paymentId']}/approve",
     {"amount": 15000, "webhookUrl": "http://reservation-api:8081/api/v1/webhooks/portone"})
for _ in range(20):
    with urllib.request.urlopen(urllib.request.Request(base + "/screenings/1/reservations/me",
                                                     headers={"Queue-Token": token}), timeout=10) as response:
        if json.load(response)["status"] == "CONFIRMED":
            break
    time.sleep(.5)
else:
    raise SystemExit("Redis did not confirm the payment")

after_kill = snapshot("worker-after-kill")
run("docker", "compose", "up", "-d", "reservation-worker")
for _ in range(30):
    try:
        with urllib.request.urlopen("http://localhost:8084/actuator/health", timeout=1) as response:
            if response.status == 200:
                break
    except Exception:
        time.sleep(.5)
else:
    raise SystemExit("Worker did not restart")
time.sleep(2)
after_restart = snapshot("worker-after-restart")
before_missing = set(before["missingMysqlSeats"])
if seat in before_missing or seat not in set(after_kill["missingMysqlSeats"]):
    raise SystemExit("Lost seat was not isolated in the after-kill snapshot")
if seat not in set(after_restart["missingMysqlSeats"]):
    raise SystemExit("Pub/Sub unexpectedly replayed the missing reservation")
print("V3 Pub/Sub loss reproduced after worker SIGKILL and restart")
