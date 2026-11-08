#!/usr/bin/env python3
"""Replay a committed Stream event and verify no second DB decrement."""
import json
from pathlib import Path
import subprocess
import time

root = Path(__file__).resolve().parents[1]
stream = "stream:{1}:reservations"


def run(*args):
    return subprocess.run(args, cwd=root, capture_output=True, text=True, check=True).stdout.strip()


def redis_json(*args):
    return json.loads(run("docker", "compose", "exec", "-T", "redis", "redis-cli", "--json", *args))


def mysql(sql):
    return run("docker", "compose", "exec", "-T", "-e", "MYSQL_PWD=root", "mysql",
               "mysql", "-uroot", "-N", "-B", "movie_seats", "-e", sql)


latest = redis_json("XREVRANGE", stream, "+", "-", "COUNT", "1")[0]
payload = latest[1][1]
event = json.loads(payload)
payment_id = event["paymentId"]
if mysql(f"SELECT COUNT(*) FROM reservations WHERE payment_id='{payment_id}'") != "1":
    raise SystemExit("Latest event is not yet persisted")
before = int(mysql("SELECT remaining_seat_count FROM screenings WHERE id='1'"))
new_id = run("docker", "compose", "exec", "-T", "redis", "redis-cli", "XADD", stream, "*", "event", payload)
for _ in range(30):
    groups = redis_json("XINFO", "GROUPS", stream)
    pending = redis_json("XPENDING", stream, "workers")[0]
    if groups[0]["last-delivered-id"] == new_id and pending == 0:
        break
    time.sleep(.5)
else:
    raise SystemExit("Duplicate event was not ACKed")
after = int(mysql("SELECT remaining_seat_count FROM screenings WHERE id='1'"))
if after != before or mysql(f"SELECT COUNT(*) FROM reservations WHERE payment_id='{payment_id}'") != "1":
    raise SystemExit("Duplicate event changed DB seat count")
run(str(root / "load-test/snapshot.sh"), "v4-streams", "stream-duplicate-acked")
print("V4 duplicate Stream event ACKed without second reservation or decrement")
