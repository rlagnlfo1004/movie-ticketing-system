#!/usr/bin/env python3
"""Check that reversed and disjoint seat requests no longer deadlock in V2."""
import concurrent.futures
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from local_env import get as local_env

root = Path(__file__).resolve().parents[1]


def mysql(sql):
    return subprocess.run(["docker", "compose", "exec", "-T", "-e", f"MYSQL_PWD={local_env('SEAT_DB_ROOT_PASSWORD')}", "mysql",
                           "mysql", "-uroot", "-N", "-B", "movie_seats", "-e", sql], cwd=root,
                          capture_output=True, text=True, check=True).stdout.strip()


def deadlocks():
    return int(mysql("SELECT count FROM information_schema.innodb_metrics WHERE name='lock_deadlocks'"))


def seed(token):
    subprocess.run(["docker", "compose", "exec", "-T", "redis", "redis-cli", "SET",
                    f"waiting:screening:1:active:{token}", token, "EX", "600"],
                   cwd=root, capture_output=True, check=True)


def hold(token, seats):
    req = urllib.request.Request("http://localhost:8000/api/v1/screenings/1/holds", method="POST",
                                 data=json.dumps({"seats": seats}).encode(),
                                 headers={"Content-Type": "application/json", "Queue-Token": token,
                                          "Idempotency-Key": f"v2-lock-{uuid.uuid4()}"})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


with urllib.request.urlopen("http://localhost:8000/api/v1/screenings/1/seats", timeout=10) as response:
    available = {seat["seatNo"] for seat in json.load(response)["seats"] if seat["status"] == "AVAILABLE"}

before = deadlocks()
for mode, row in (("row", "K"), ("fk", "L")):
    pairs = [(f"{row}-{number}", f"{row}-{number + 1}") for number in range(1, 40, 2)
             if f"{row}-{number}" in available and f"{row}-{number + 1}" in available]
    if len(pairs) < 3:
        raise SystemExit(f"Not enough fresh {row} seats")
    for first_seat, second_seat in pairs[:3]:
        first, second = f"v2-{uuid.uuid4()}", f"v2-{uuid.uuid4()}"
        seed(first)
        seed(second)
        choices = ([first_seat, second_seat], [second_seat, first_seat]) if mode == "row" else ([first_seat], [second_seat])
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = [pool.submit(hold, first, choices[0]), pool.submit(hold, second, choices[1])]
            statuses = [future.result() for future in results]
        if 500 in statuses or any(status not in (201, 409) for status in statuses):
            raise SystemExit(f"Unexpected V2 {mode} result: {statuses}")
        print(f"{mode}: {statuses}")
        time.sleep(.1)
after = deadlocks()
if after != before:
    raise SystemExit(f"Deadlocks increased from {before} to {after}")
print("V2 row-order and FK S→X deadlock paths stayed clear")
