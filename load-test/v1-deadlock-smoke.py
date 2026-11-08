#!/usr/bin/env python3
"""Trigger V1 lock-order and screening FK S→X deadlocks through the public hold API."""
import concurrent.futures
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

mode = sys.argv[1] if len(sys.argv) > 1 else "row"
if mode not in ("row", "fk"):
    raise SystemExit("usage: v1-deadlock-smoke.py row|fk")

for _ in range(30):
    try:
        with urllib.request.urlopen("http://localhost:8081/actuator/health", timeout=1) as ready:
            if ready.status == 200:
                break
    except (urllib.error.URLError, TimeoutError, ConnectionResetError):
        time.sleep(1)
else:
    raise SystemExit("reservation-api did not become healthy")


def seed(token):
    subprocess.run(["docker", "compose", "exec", "-T", "redis", "redis-cli", "SET",
                    f"waiting:screening:1:active:{token}", token, "EX", "600"],
                   check=True, capture_output=True)


def hold(token, seats):
    request = urllib.request.Request(
        "http://localhost:8000/api/v1/screenings/1/holds",
        method="POST", data=json.dumps({"seats": seats}).encode(),
        headers={"Content-Type": "application/json", "Queue-Token": token,
                 "Idempotency-Key": f"deadlock-{uuid.uuid4()}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


deadlocks = 0
for pair in range(5):
    first = f"deadlock-{uuid.uuid4()}"
    second = f"deadlock-{uuid.uuid4()}"
    seed(first)
    seed(second)
    a, b = f"G-{pair * 2 + 1}", f"G-{pair * 2 + 2}"
    selections = ([a, b], [b, a]) if mode == "row" else ([a], [b])
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(hold, first, selections[0]), pool.submit(hold, second, selections[1])]
        statuses = [future.result() for future in futures]
    print(f"pair {pair + 1}: {statuses}")
    if 500 in statuses:
        deadlocks += 1
        break
    time.sleep(0.1)

if deadlocks == 0:
    raise SystemExit("No deadlock response; inspect InnoDB status and increase the test-only pause")
print(f"V1 {mode} deadlock reproduced")
