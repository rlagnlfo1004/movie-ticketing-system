#!/usr/bin/env python3
"""Hold a seat row while the HTTP hold request waits, then capture data_locks."""
import concurrent.futures
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid
from local_env import get as local_env

root = Path(__file__).resolve().parents[1]
seat = "H-1"
token = f"lock-{uuid.uuid4()}"
subprocess.run(["docker", "compose", "exec", "-T", "redis", "redis-cli", "SET",
                f"waiting:screening:1:active:{token}", token, "EX", "600"],
               cwd=root, check=True, capture_output=True)

blocker = subprocess.Popen([
    "docker", "compose", "exec", "-T", "-e", f"MYSQL_PWD={local_env('SEAT_DB_ROOT_PASSWORD')}", "mysql", "mysql", "-uroot", "movie_seats",
    "-e", f"START TRANSACTION; SELECT status FROM screening_seats WHERE screening_id='1' AND seat_no='{seat}' FOR UPDATE; DO SLEEP(6); COMMIT;",
], cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def request_hold():
    request = urllib.request.Request(
        "http://localhost:8000/api/v1/screenings/1/holds", method="POST",
        headers={"Content-Type": "application/json", "Queue-Token": token,
                 "Idempotency-Key": f"lock-key-{uuid.uuid4()}"},
        data=json.dumps({"seats": [seat]}).encode(),
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.status


time.sleep(1)
with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
    future = pool.submit(request_hold)
    time.sleep(1)
    subprocess.run([str(root / "load-test/snapshot.sh"), "v1-db", "hold-contention"], cwd=root, check=True)
    status = future.result()
blocker.wait(timeout=10)
evidence = json.loads((root / "docs/evidence/v1-db/hold-contention.json").read_text())
if not evidence["waits"] or status != 201:
    raise SystemExit(f"Expected one waiting hold and final 201, got waits={len(evidence['waits'])}, status={status}")
print("V1 row-lock wait captured")
