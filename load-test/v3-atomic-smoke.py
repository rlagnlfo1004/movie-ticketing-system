#!/usr/bin/env python3
"""Check all-or-nothing Redis holds, conflict response, and same-key replay."""
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1]
base = "http://localhost:8000/api/v1"
with urllib.request.urlopen(base + "/screenings/1/seats", timeout=10) as response:
    available = {item["seatNo"] for item in json.load(response)["seats"] if item["status"] == "AVAILABLE"}
group = next([f"P-{n + offset}" for offset in range(4)] for n in range(1, 38)
             if all(f"P-{n + offset}" in available for offset in range(4)))


def seed():
    token = f"atomic-{uuid.uuid4()}"
    subprocess.run(["docker", "compose", "exec", "-T", "redis", "redis-cli", "SET",
                    f"waiting:screening:1:active:{token}", token, "EX", "600"],
                   cwd=root, capture_output=True, check=True)
    return token


def hold(token, key, seats):
    request = urllib.request.Request(base + "/screenings/1/holds", method="POST",
                                     data=json.dumps({"seats": seats}).encode(),
                                     headers={"Content-Type": "application/json", "Queue-Token": token,
                                              "Idempotency-Key": key})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


first = seed()
second = seed()
key = f"atomic-key-{uuid.uuid4()}"
status, existing = hold(first, key, [group[2]])
assert status == 201
replay_status, replay = hold(first, key, [group[2]])
assert replay_status == 201 and replay["holdId"] == existing["holdId"]
conflict_status, conflict = hold(second, f"atomic-key-{uuid.uuid4()}", group)
assert conflict_status == 409 and conflict["code"] == "SEAT_TAKEN"
with urllib.request.urlopen(base + "/screenings/1/seats", timeout=10) as response:
    states = {item["seatNo"]: item["status"] for item in json.load(response)["seats"]}
assert all(states[seat] == "AVAILABLE" for seat in (group[0], group[1], group[3]))
print("V3 Redis hold is atomic and same-key replay is stable")
