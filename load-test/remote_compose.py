#!/usr/bin/env python3
"""Route Compose exec calls to the correct private-service host over SSH."""
import os
from pathlib import Path
import shlex

REMOTE_ROOT = "/home/ec2-user/movie-ticketing"
SERVICE_ROLE = {"mysql": "MYSQL", "redis": "REDIS", "reservation-api": "APP_RESERVATION",
                "reservation-worker": "APP_RESERVATION", "nginx": "EDGE"}


def hosts():
    path = Path(os.environ["MEASUREMENT_HOSTS_ENV"])
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line)


def route(args):
    if not os.environ.get("MEASUREMENT_HOSTS_ENV"):
        return list(args)
    items = list(args)
    if items[:3] != ["docker", "compose", "exec"]:
        return items
    index = 3
    while index < len(items) and items[index].startswith("-"):
        index += 2 if items[index] == "-e" else 1
    if index >= len(items):
        raise ValueError("Compose exec service is missing")
    role = SERVICE_ROLE.get(items[index])
    if not role:
        raise ValueError(f"No remote host for service {items[index]}")
    address = hosts()[f"{role}_PUBLIC_IP"]
    key = os.environ["MEASUREMENT_SSH_KEY"]
    user = os.environ.get("MEASUREMENT_SSH_USER", "ec2-user")
    remote = ["docker", "compose", "-f", f"{REMOTE_ROOT}/compose.json", "exec", *items[3:]]
    return ["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
            "-i", key, f"{user}@{address}", shlex.join(remote)]
