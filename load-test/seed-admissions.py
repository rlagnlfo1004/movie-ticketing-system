#!/usr/bin/env python3
"""Preload scenario B admission tokens through Redis RESP; no requests during measurement."""
import os
import sys
import time

screening_id = os.environ.get("SCREENING_ID", "1")
seconds = int(os.environ.get("STAGE_SECONDS", "60"))
expires_at = int(time.time() * 1000) + 30 * 60 * 1000


def command(*parts):
    encoded = [str(part).encode() for part in parts]
    sys.stdout.buffer.write(f"*{len(encoded)}\r\n".encode())
    for part in encoded:
        sys.stdout.buffer.write(f"${len(part)}\r\n".encode() + part + b"\r\n")


for rate in (100, 200, 400, 800, 1600, 3200):
    for iteration in range(rate * seconds + max(100, rate // 10)):
        token = f"seed-rate_{rate}-{iteration:07d}"
        command("SET", f"waiting:screening:{screening_id}:active:{token}", token, "PX", 1800000)
        command("ZADD", f"waiting:screening:{screening_id}:active:index", expires_at, token)
