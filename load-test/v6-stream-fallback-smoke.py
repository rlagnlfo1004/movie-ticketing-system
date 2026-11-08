#!/usr/bin/env python3
"""Recover a paid V4/V5-style payment from its Stream event without PG customData."""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1]
base = "http://localhost:8000/api/v1"


def run(*args, env=None):
    return subprocess.run(args, cwd=root, env=env, capture_output=True, text=True, check=True).stdout.strip()


def post(url, data, headers=None):
    request = urllib.request.Request(url, method="POST", data=json.dumps(data).encode(),
                                     headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=45) as response:
        return json.load(response)


slow = os.environ.copy()
slow["WORKER_TEST_WRITE_PAUSE"] = "10s"
run("docker", "compose", "up", "-d", "reservation-worker", env=slow)
try:
    for _ in range(30):
        try:
            with urllib.request.urlopen("http://localhost:8084/actuator/health", timeout=1) as response:
                if response.status == 200:
                    break
        except Exception:
            time.sleep(.5)
    else:
        raise SystemExit("Slow worker did not start")
    with urllib.request.urlopen(base + "/screenings/1/seats", timeout=10) as response:
        seat = next(item["seatNo"] for item in json.load(response)["seats"] if item["status"] == "AVAILABLE")
    token = f"fallback-{uuid.uuid4()}"
    run("docker", "compose", "exec", "-T", "redis", "redis-cli", "SET",
        f"waiting:screening:1:active:{token}", token, "EX", "600")
    hold = post(base + "/screenings/1/holds", {"seats": [seat]},
                {"Queue-Token": token, "Idempotency-Key": f"fallback-key-{uuid.uuid4()}"})
    payment_id = hold["paymentId"]
    post(f"http://localhost:8082/test/payments/{payment_id}/approve",
         {"amount": 15000, "webhookUrl": "http://reservation-api:8081/api/v1/webhooks/invalid"})
    post(base + f"/payments/{payment_id}/complete", {})
    result = post("http://localhost:8081/admin/reconcile", {})
    if payment_id not in result["confirmedPayments"]:
        raise SystemExit("Stream fallback did not recover the paid payment")
    run(str(root / "load-test/snapshot.sh"), "v6-aof-reconcile", "pg-stream-fallback")
    print("V6 recovered a paid payment from its retained Stream event")
finally:
    run("docker", "compose", "up", "-d", "reservation-worker")
