#!/usr/bin/env python3
"""Confirm Redis seats while MySQL is down, then verify Stream catch-up."""
import json
import os
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


def snapshot(moment, mysql_unavailable=False):
    env = os.environ.copy()
    if mysql_unavailable:
        env["MYSQL_UNAVAILABLE"] = "1"
    run(str(root / "load-test/snapshot.sh"), "v4-streams", moment, env=env)
    return json.loads((root / "docs/evidence/v4-streams" / f"{moment}.json").read_text())


def post(url, data, headers=None):
    request = urllib.request.Request(url, method="POST", data=json.dumps(data).encode(),
                                     headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def confirm(seat):
    token = f"outage-{uuid.uuid4()}"
    run("docker", "compose", "exec", "-T", "redis", "redis-cli", "SET",
        f"waiting:screening:1:active:{token}", token, "EX", "600")
    hold = post(base + "/screenings/1/holds", {"seats": [seat]},
                {"Queue-Token": token, "Idempotency-Key": f"outage-key-{uuid.uuid4()}"})
    post(f"http://localhost:8082/test/payments/{hold['paymentId']}/approve",
         {"amount": 15000, "webhookUrl": "http://reservation-api:8081/api/v1/webhooks/portone"})
    for _ in range(20):
        with urllib.request.urlopen(urllib.request.Request(base + "/screenings/1/reservations/me",
                                                         headers={"Queue-Token": token}), timeout=10) as response:
            if json.load(response)["status"] == "CONFIRMED":
                return
        time.sleep(.5)
    raise SystemExit("Redis confirmation failed while MySQL was down")


with urllib.request.urlopen(base + "/screenings/1/seats", timeout=10) as response:
    seats = [item["seatNo"] for item in json.load(response)["seats"] if item["status"] == "AVAILABLE"][:3]
before = snapshot("mysql-before-outage")
run("docker", "compose", "stop", "mysql")
try:
    confirm(seats[0])
    for _ in range(20):
        if redis_json("XPENDING", stream, "workers")[0] > 0:
            break
        time.sleep(.5)
    else:
        raise SystemExit("Worker did not leave the failed write in PEL")
    confirm(seats[1])
    confirm(seats[2])
    during = snapshot("mysql-down", mysql_unavailable=True)
    if during["streamLength"] <= before["streamLength"]:
        raise SystemExit("Stream did not grow during MySQL outage")
    if not during["streamGroups"] or during["streamGroups"][0]["lag"] < 1:
        raise SystemExit("Stream lag did not grow during MySQL outage")
finally:
    run("docker", "compose", "start", "mysql")

for _ in range(100):
    groups = redis_json("XINFO", "GROUPS", stream)
    pending = redis_json("XPENDING", stream, "workers")[0]
    if groups[0]["lag"] == 0 and pending == 0:
        break
    time.sleep(.5)
else:
    raise SystemExit("Stream did not drain after MySQL restart")
after = snapshot("mysql-recovered")
if any(seat in after["missingMysqlSeats"] for seat in seats):
    raise SystemExit("An outage confirmation was not persisted")
print("V4 confirmed during MySQL outage and drained Stream after restart")
