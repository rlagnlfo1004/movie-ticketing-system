#!/usr/bin/env python3
"""Verify late payment confirms a free seat and cancels an already re-held seat."""
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid
from local_env import get as local_env

root = Path(__file__).resolve().parents[1]
base = "http://localhost:8000/api/v1"


def get(path, headers=None):
    with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers or {}), timeout=10) as response:
        return json.load(response)


def post(url, data, headers=None):
    request = urllib.request.Request(url, method="POST", data=json.dumps(data).encode(),
                                     headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def hold(seat):
    token = f"late-{uuid.uuid4()}"
    subprocess.run(["docker", "compose", "exec", "-T", "redis", "redis-cli", "SET",
                    f"waiting:screening:1:active:{token}", token, "EX", "600"],
                   cwd=root, capture_output=True, check=True)
    data = post(base + "/screenings/1/holds", {"seats": [seat]},
                {"Queue-Token": token, "Idempotency-Key": f"late-key-{uuid.uuid4()}"})
    return token, data


def expire(token, hold_id):
    subprocess.run(["docker", "compose", "exec", "-T", "-e", f"MYSQL_PWD={local_env('SEAT_DB_ROOT_PASSWORD')}", "mysql",
                    "mysql", "-uroot", "movie_seats", "-e",
                    f"UPDATE seat_holds SET held_until=NOW(6)-INTERVAL 1 SECOND WHERE hold_id='{hold_id}'"],
                   cwd=root, capture_output=True, check=True)
    for _ in range(10):
        if get("/screenings/1/reservations/me", {"Queue-Token": token})["status"] == "EXPIRED":
            return
        time.sleep(.5)
    raise SystemExit("Hold did not expire")


def approve(payment_id):
    return post(f"http://localhost:8082/test/payments/{payment_id}/approve",
                {"amount": 15000, "webhookUrl": "http://reservation-api:8081/api/v1/webhooks/portone"})


def wait_status(token, expected):
    for _ in range(20):
        actual = get("/screenings/1/reservations/me", {"Queue-Token": token})["status"]
        if actual == expected:
            return
        time.sleep(.5)
    raise SystemExit(f"Expected {expected}, got {actual}")


def snapshot(moment):
    subprocess.run([str(root / "load-test/snapshot.sh"), "v2-db", moment], cwd=root, check=True)
    return json.loads((root / "docs/evidence/v2-db" / f"{moment}.json").read_text())


for _ in range(30):
    try:
        get("/screenings/1/seats")
        break
    except Exception:
        time.sleep(1)
else:
    raise SystemExit("reservation-api did not become healthy")

seats = [item["seatNo"] for item in get("/screenings/1/seats")["seats"] if item["status"] == "AVAILABLE"]
free_seat, taken_seat = seats[:2]

free_token, free_hold = hold(free_seat)
expire(free_token, free_hold["holdId"])
approve(free_hold["paymentId"])
wait_status(free_token, "CONFIRMED")
print("Late payment confirmed when the released seat remained free")

expired_token, expired_hold = hold(taken_seat)
expire(expired_token, expired_hold["holdId"])
new_token, new_hold = hold(taken_seat)
before = snapshot("late-payment-before-cancel")
approve(expired_hold["paymentId"])
wait_status(expired_token, "CANCELLED")
with urllib.request.urlopen(urllib.request.Request(
        f"http://localhost:8082/payments/{expired_hold['paymentId']}",
        headers={"Authorization": "PortOne " + local_env("MOCK_PG_API_SECRET")}), timeout=10) as response:
    pg_status = json.load(response)["status"]
if pg_status != "CANCELLED":
    raise SystemExit(f"Expected PG cancellation, got {pg_status}")
if get("/screenings/1/reservations/me", {"Queue-Token": new_token})["holdId"] != new_hold["holdId"]:
    raise SystemExit("The new holder lost the seat")
after = snapshot("late-payment-after-cancel")
before_status = next(item["status"] for item in before["recentPayments"] if item["paymentId"] == expired_hold["paymentId"])
after_status = next(item["status"] for item in after["recentPayments"] if item["paymentId"] == expired_hold["paymentId"])
if (before_status, after_status) != ("READY", "CANCELLED"):
    raise SystemExit(f"Unexpected local payment transition: {before_status} -> {after_status}")
print("Late payment cancelled when another holder took the seat")
