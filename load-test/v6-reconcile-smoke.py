#!/usr/bin/env python3
"""Exercise PG-driven recovery, cancellation, Redis SIGKILL, and AOF restart."""
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid
from local_env import get as local_env

root = Path(__file__).resolve().parents[1]
base = "http://localhost:8000/api/v1"


def run(*args):
    return subprocess.run(args, cwd=root, capture_output=True, text=True, check=True).stdout.strip()


def redis(*args):
    return run("docker", "compose", "exec", "-T", "redis", "redis-cli", *args)


def mysql(sql):
    return run("docker", "compose", "exec", "-T", "-e", f"MYSQL_PWD={local_env('SEAT_DB_ROOT_PASSWORD')}", "mysql",
               "mysql", "-uroot", "-N", "-B", "movie_seats", "-e", sql)


def get(path, headers=None):
    with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers or {}), timeout=15) as response:
        return json.load(response)


def post(url, data, headers=None):
    request = urllib.request.Request(url, method="POST", data=json.dumps(data).encode(),
                                     headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def hold(seat):
    token = f"v6-{uuid.uuid4()}"
    redis("SET", f"waiting:screening:1:active:{token}", token, "EX", "600")
    result = post(base + "/screenings/1/holds", {"seats": [seat]},
                  {"Queue-Token": token, "Idempotency-Key": f"v6-key-{uuid.uuid4()}"})
    if not result.get("customData", {}).get("ticketingContext"):
        raise SystemExit("Hold did not return an opaque PG context")
    return token, result


def expire(token, seat):
    redis("PEXPIRE", f"seat:{{1}}:hold:{seat}", "1")
    redis("PEXPIRE", f"token:{{1}}:{token}:hold", "1")
    time.sleep(.1)


def forget_intent(token, hold_data):
    payment_id = hold_data["paymentId"]
    redis("DEL", f"hold:{{1}}:{hold_data['holdId']}", f"payment:{{1}}:{payment_id}:hold",
          f"payment:screening:{payment_id}", f"token:{{1}}:{token}:last_hold")


def approve(hold_data):
    return post(f"http://localhost:8082/test/payments/{hold_data['paymentId']}/approve",
                {"amount": hold_data["amount"], "customData": hold_data["customData"],
                 "webhookUrl": "http://reservation-api:8081/api/v1/webhooks/invalid"})


def snapshot(moment):
    run(str(root / "load-test/snapshot.sh"), "v6-aof-reconcile", moment)
    return json.loads((root / "docs/evidence/v6-aof-reconcile" / f"{moment}.json").read_text())


available = [item["seatNo"] for item in get("/screenings/1/seats")["seats"] if item["status"] == "AVAILABLE"]
free_seat, taken_seat = available[:2]

free_token, free_hold = hold(free_seat)
expire(free_token, free_seat)
approve(free_hold)
forget_intent(free_token, free_hold)
free_result = post("http://localhost:8081/admin/reconcile", {})
if free_hold["paymentId"] not in free_result["confirmedPayments"]:
    raise SystemExit("PG-paid free seat was not recovered")
if get("/screenings/1/reservations/me", {"Queue-Token": free_token})["status"] != "CONFIRMED":
    raise SystemExit("Recovered free seat is not confirmed")
snapshot("pg-recovered-free")

old_token, old_hold = hold(taken_seat)
expire(old_token, taken_seat)
new_token, new_hold = hold(taken_seat)
approve(old_hold)
forget_intent(old_token, old_hold)
taken_result = post("http://localhost:8081/admin/reconcile", {})
if old_hold["paymentId"] not in taken_result["cancelledPayments"]:
    raise SystemExit("PG-paid taken seat was not cancelled")
if get("/screenings/1/reservations/me", {"Queue-Token": new_token})["holdId"] != new_hold["holdId"]:
    raise SystemExit("New holder lost the seat")
snapshot("pg-cancelled-taken")

before = snapshot("redis-before-kill")
run("docker", "compose", "kill", "-s", "SIGKILL", "redis")
run("docker", "compose", "up", "-d", "redis")
for _ in range(30):
    try:
        if redis("PING") == "PONG":
            break
    except Exception:
        time.sleep(.5)
else:
    raise SystemExit("Redis did not restart")
snapshot("redis-after-restart")
get("/screenings/1/seats")  # run_id change forces reconciliation before serving seats
after = snapshot("redis-after-reconcile")
if after["unresolvedPgPayments"]:
    raise SystemExit("An approved PG payment remains unresolved")
if len(after["redisReservedSeats"]) != len(after["mysqlReservationSeats"]):
    raise SystemExit("Redis and MySQL confirmed seat counts differ")
if int(redis("CONFIG", "GET", "appendonly").splitlines()[-1] == "yes") != 1:
    raise SystemExit("Redis AOF is not enabled")
if before["redisReservedSeats"] != after["redisReservedSeats"]:
    raise SystemExit("Confirmed seats changed across Redis SIGKILL and reconciliation")
print("V6 PG reconciliation and Redis AOF SIGKILL recovery passed")
