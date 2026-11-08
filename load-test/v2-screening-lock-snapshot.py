#!/usr/bin/env python3
"""Capture the screening-row queue while a burst of V2 holds waits behind one transaction."""
import concurrent.futures
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1]
with urllib.request.urlopen("http://localhost:8000/api/v1/screenings/1/seats", timeout=10) as response:
    seat = next(item["seatNo"] for item in json.load(response)["seats"] if item["status"] == "AVAILABLE")


def resp(*parts):
    encoded = [str(part).encode() for part in parts]
    return f"*{len(parts)}\r\n".encode() + b"".join(
        f"${len(part)}\r\n".encode() + part + b"\r\n" for part in encoded)


tokens = [f"ceiling-{uuid.uuid4()}" for _ in range(120)]
commands = b"".join(resp("SET", f"waiting:screening:1:active:{token}", token, "EX", 600) for token in tokens)
subprocess.run(["docker", "compose", "exec", "-T", "redis", "redis-cli", "--pipe"],
               cwd=root, input=commands, capture_output=True, check=True)

blocker = subprocess.Popen(["docker", "compose", "exec", "-T", "-e", "MYSQL_PWD=root", "mysql",
                           "mysql", "-uroot", "movie_seats", "-e",
                           "START TRANSACTION; SELECT id FROM screenings WHERE id='1' FOR UPDATE; DO SLEEP(8); COMMIT;"],
                          cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def hold(token):
    request = urllib.request.Request("http://localhost:8000/api/v1/screenings/1/holds", method="POST",
                                     data=json.dumps({"seats": [seat]}).encode(),
                                     headers={"Content-Type": "application/json", "Queue-Token": token,
                                              "Idempotency-Key": f"ceiling-{uuid.uuid4()}"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except TimeoutError:
        return 0


time.sleep(1)
with concurrent.futures.ThreadPoolExecutor(max_workers=120) as pool:
    futures = [pool.submit(hold, token) for token in tokens]
    time.sleep(2)
    subprocess.run([str(root / "load-test/snapshot.sh"), "v2-db", "throughput-ceiling"], cwd=root, check=True)
    statuses = [future.result() for future in futures]
blocker.wait(timeout=12)
evidence = json.loads((root / "docs/evidence/v2-db/throughput-ceiling.json").read_text())
if not evidence["waits"]:
    raise SystemExit("No screening-row lock wait captured")
print(f"Captured screening-row wait; completed request statuses: {sorted(set(statuses))}")
