#!/usr/bin/env python3
"""Check V7 payment flow, Sentinel promotion, old-primary fencing, and seat balance."""
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ["docker", "compose", "-f", "docker-compose.yml", "-f", "docker-compose.v7.yml"]
BASE = "http://localhost:8000/api/v1"
DATA_SERVICES = ("redis", "redis-replica-1", "redis-replica-2")
SENTINELS = ("sentinel-1", "sentinel-2", "sentinel-3")


def compose(*args, check=True):
    return subprocess.run([*COMPOSE, *args], cwd=ROOT, capture_output=True, text=True, check=check)


def redis(service, *args):
    return compose("exec", "-T", service, "redis-cli", *args, check=False)


def request(method, url, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    call = urllib.request.Request(url, data=data, method=method,
                                  headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(call, timeout=20) as response:
        return json.load(response)


def sentinel_report(service):
    result = redis(service, "-p", "26379", "--raw", "SENTINEL", "master", "movie-primary")
    if result.returncode:
        return None
    rows = result.stdout.splitlines()
    return dict(zip(rows[::2], rows[1::2]))


def majority():
    reports = [sentinel_report(service) for service in SENTINELS]
    values = [(item.get("ip"), item.get("config-epoch")) for item in reports
              if item and item.get("flags") == "master"]
    return next((value for value in values if values.count(value) >= 2), None)


def service_ip(service):
    container = compose("ps", "-a", "-q", service).stdout.strip()
    result = subprocess.run(["docker", "inspect", "--format",
                             "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", container],
                            capture_output=True, text=True, check=True)
    return result.stdout.strip()


def e2e():
    token = request("POST", BASE + "/screenings/1/queue/register", {})["token"]
    for _ in range(30):
        state = request("GET", BASE + "/screenings/1/queue/status?token=" + token)
        if state.get("status") == "ACTIVE":
            break
        time.sleep(1)
    else:
        raise RuntimeError("Queue token did not become active")
    seats = request("GET", BASE + "/screenings/1/seats")["seats"]
    seat = next(item["seatNo"] for item in seats if item["status"] == "AVAILABLE")
    hold = request("POST", BASE + "/screenings/1/holds", {"seats": [seat]},
                   {"Queue-Token": token, "Idempotency-Key": "v7-" + str(uuid.uuid4())})
    request("POST", "http://localhost:8082/test/payments/" + hold["paymentId"] + "/approve",
            {"amount": hold["amount"], "customData": hold["customData"],
             "webhookUrl": "http://reservation-api:8081/api/v1/webhooks/portone"})
    for _ in range(30):
        booking = request("GET", BASE + "/screenings/1/reservations/me",
                          headers={"Queue-Token": token})
        if booking.get("status") == "CONFIRMED":
            return seat
        time.sleep(1)
    raise RuntimeError("Webhook did not confirm the reservation")


def db_seats():
    result = compose("exec", "-T", "mysql", "sh", "-lc",
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B movie_seats '
                     '-e "SELECT COUNT(*) FROM reservation_seats;"')
    return int(result.stdout.strip())


def main():
    compose("exec", "-T", "nginx", "nginx", "-s", "reload", check=False)
    for _ in range(60):
        try:
            request("GET", BASE + "/screenings/1/seats")
            break
        except Exception:
            time.sleep(1)
    else:
        raise RuntimeError("Local reservation API did not become ready")
    first = majority()
    if first is None:
        raise RuntimeError("Sentinel majority is unavailable")
    first_service = next(service for service in DATA_SERVICES if service_ip(service) == first[0])
    state = sentinel_report("sentinel-1")
    if state.get("num-slaves") != "2" or state.get("num-other-sentinels") != "2":
        raise RuntimeError("Sentinel has not discovered two replicas and two peers")
    before_seat = e2e()
    started = time.monotonic()
    compose("kill", "-s", "SIGKILL", first_service)
    try:
        promoted = None
        for _ in range(90):
            current = majority()
            if current and current[0] != first[0] and int(current[1]) > int(first[1]):
                promoted = current
                break
            time.sleep(1)
        if promoted is None:
            raise RuntimeError("Sentinel did not promote a replica")
        promotion_seconds = round(time.monotonic() - started, 3)
        new_service = next(service for service in DATA_SERVICES if service_ip(service) == promoted[0])
        for _ in range(30):
            role = redis(new_service, "INFO", "replication").stdout
            if "role:master" in role and "connected_slaves:1" in role:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Promoted primary has no online replica")
        after_seat = e2e()
        redis_count = int(redis(new_service, "SCARD", "seat:{1}:reserved").stdout.strip())
        mysql_count = db_seats()
        if redis_count != mysql_count:
            raise RuntimeError(f"Redis/MySQL confirmed seats differ: {redis_count}/{mysql_count}")
        floor = int(redis(new_service, "GET", "waiting:screening:1:admission:fence").stdout.strip())
        if floor < int(promoted[1]):
            raise RuntimeError("Admission fence did not advance after failover")
    finally:
        compose("up", "-d", first_service)
    for _ in range(45):
        role = redis(first_service, "INFO", "replication").stdout
        if "role:slave" in role:
            break
        time.sleep(1)
    else:
        raise RuntimeError("Former primary did not return as a replica")
    old_write = redis(first_service, "SET", "v7:old-primary-test", "rejected")
    if "READONLY" not in old_write.stdout + old_write.stderr:
        raise RuntimeError("Former primary accepted a write")
    print(json.dumps({"beforeSeat": before_seat, "afterSeat": after_seat,
                      "promotionSeconds": promotion_seconds, "epochBefore": int(first[1]),
                      "epochAfter": int(promoted[1]), "redisSeats": redis_count,
                      "mysqlSeats": mysql_count, "formerPrimaryWrite": "READONLY"}))


if __name__ == "__main__":
    main()
