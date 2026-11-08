#!/usr/bin/env python3
"""Expire Redis hold keys and check late-payment confirmation or cancellation."""
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1]
base = "http://localhost:8000/api/v1"


def api(path, headers=None):
    with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers or {}), timeout=10) as response:
        return json.load(response)


def post(url, data, headers=None):
    request = urllib.request.Request(url, method="POST", data=json.dumps(data).encode(),
                                     headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def redis(*args):
    return subprocess.run(["docker", "compose", "exec", "-T", "redis", "redis-cli", *args],
                          cwd=root, capture_output=True, text=True, check=True).stdout.strip()


def hold(seat):
    token = f"late-redis-{uuid.uuid4()}"
    redis("SET", f"waiting:screening:1:active:{token}", token, "EX", "600")
    result = post(base + "/screenings/1/holds", {"seats": [seat]},
                  {"Queue-Token": token, "Idempotency-Key": f"late-key-{uuid.uuid4()}"})
    return token, result


def expire(token, seat):
    redis("PEXPIRE", f"seat:{{1}}:hold:{seat}", "1")
    redis("PEXPIRE", f"token:{{1}}:{token}:hold", "1")
    time.sleep(.1)


def approve(payment_id):
    post(f"http://localhost:8082/test/payments/{payment_id}/approve",
         {"amount": 15000, "webhookUrl": "http://reservation-api:8081/api/v1/webhooks/portone"})


def wait(token, expected):
    for _ in range(20):
        actual = api("/screenings/1/reservations/me", {"Queue-Token": token})["status"]
        if actual == expected:
            return
        time.sleep(.5)
    raise SystemExit(f"Expected {expected}, got {actual}")


seats = [item["seatNo"] for item in api("/screenings/1/seats")["seats"] if item["status"] == "AVAILABLE"]
free, taken = seats[:2]
free_token, free_hold = hold(free)
expire(free_token, free)
approve(free_hold["paymentId"])
wait(free_token, "CONFIRMED")

old_token, old_hold = hold(taken)
expire(old_token, taken)
new_token, new_hold = hold(taken)
approve(old_hold["paymentId"])
wait(old_token, "CANCELLED")
if api("/screenings/1/reservations/me", {"Queue-Token": new_token})["holdId"] != new_hold["holdId"]:
    raise SystemExit("New holder lost the seat")
print("V3 late payment confirmed a free seat and cancelled a taken seat")
