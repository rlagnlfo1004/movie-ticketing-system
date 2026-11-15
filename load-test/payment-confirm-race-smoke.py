#!/usr/bin/env python3
"""Race signed webhooks with completion requests for 100 distinct payments."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import hmac
import json
from pathlib import Path
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid

from local_env import get as local_env


ROOT = Path(__file__).resolve().parents[1]
API = "http://localhost:8081/api/v1"
PG = "http://localhost:8082"
STREAM = "stream:{1}:reservations"


def compose(*args):
    return subprocess.run(["docker", "compose", *args], cwd=ROOT, check=True,
                          capture_output=True, text=True).stdout.strip()


def redis(*args):
    return compose("exec", "-T", "redis", "redis-cli", "--json", *args)


def mysql(sql):
    return compose("exec", "-T", "mysql", "sh", "-lc",
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B movie_seats -e ' +
                   "'" + sql.replace("'", "'\\''") + "'")


def request(method, url, body=None, headers=None):
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    req = Request(url, data=data, method=method,
                  headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urlopen(req, timeout=20) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
    except HTTPError as error:
        raw = error.read()
        try:
            detail = json.loads(raw)
        except ValueError:
            detail = raw.decode(errors="replace")
        return error.code, detail


def signed_webhook(payment_id):
    event_id = "msg_" + str(uuid.uuid4())
    timestamp = str(int(time.time()))
    body = json.dumps({"type": "Transaction.Paid", "timestamp": datetime.now(timezone.utc).isoformat(),
                       "data": {"storeId": "store-mock", "paymentId": payment_id}}, separators=(",", ":"))
    key = base64.b64decode(local_env("MOCK_PG_WEBHOOK_SECRET").removeprefix("whsec_"))
    signature = base64.b64encode(hmac.new(key, f"{event_id}.{timestamp}.{body}".encode(), hashlib.sha256).digest()).decode()
    return request("POST", API + "/webhooks/portone", json.loads(body),
                   {"webhook-id": event_id, "webhook-timestamp": timestamp,
                    "webhook-signature": "v1," + signature})


def wait_for_db(payment_id):
    for _ in range(50):
        rows = mysql("SELECT reservation_id FROM reservations WHERE payment_id='" + payment_id + "'")
        if rows:
            return rows.splitlines()
        time.sleep(.1)
    return []


def run_one(seat, ordinal, callers):
    token = f"payment-race-{uuid.uuid4()}"
    redis("SET", f"waiting:screening:1:active:{token}", token, "EX", "600")
    status, hold = request("POST", API + "/screenings/1/holds", {"seats": [seat]},
                           {"Queue-Token": token, "Idempotency-Key": "race-" + token})
    if status not in (200, 201):
        raise RuntimeError(f"hold {ordinal}: {status} {hold}")
    payment_id = hold["paymentId"]
    status, approved = request("POST", PG + f"/test/payments/{payment_id}/approve",
                               {"amount": hold["amount"], "customData": hold.get("customData"),
                                "webhookUrl": "http://reservation-api:8081/api/v1/webhooks/portone"})
    if status != 200:
        raise RuntimeError(f"approve {ordinal}: {status} {approved}")
    with ThreadPoolExecutor(max_workers=callers + 1) as pool:
        futures = [pool.submit(request, "POST", API + f"/payments/{payment_id}/complete", {})
                   for _ in range(callers)]
        webhook = pool.submit(signed_webhook, payment_id)
        replies = [future.result() for future in futures]
        webhook_reply = webhook.result()
    late_status, late = request("POST", API + f"/payments/{payment_id}/complete", {})
    db_ids = wait_for_db(payment_id)
    redis_value = json.loads(json.loads(redis("GET", f"payment:{{1}}:{payment_id}")))
    stream_entries = json.loads(redis("XRANGE", STREAM, "-", "+"))
    event_ids = []
    for _, fields in stream_entries:
        event = json.loads(dict(zip(fields[::2], fields[1::2]))["event"])
        if event["paymentId"] == payment_id:
            event_ids.append(event["reservationId"])
    api_ids = [body.get("reservationId") for status, body in replies if status == 200]
    expected = db_ids[0] if len(db_ids) == 1 else None
    valid = (len(api_ids) == callers and all(value == expected for value in api_ids)
             and webhook_reply[0] == 200 and late_status == 200
             and late.get("reservationId") == expected
             and redis_value.get("reservationId") == expected
             and event_ids == [expected])
    return {"paymentId": payment_id, "reservationId": expected, "api200": len(api_ids),
            "apiStatuses": [status for status, _ in replies], "webhookStatus": webhook_reply[0],
            "lateStatus": late_status, "redisReservationId": redis_value.get("reservationId"),
            "streamReservationIds": event_ids, "dbReservationIds": db_ids, "passed": valid}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--payments", type=int, default=100)
    parser.add_argument("--callers", type=int, default=20)
    args = parser.parse_args()
    if args.payments < 1 or args.callers < 1:
        parser.error("payments and callers must be positive")
    status, seats = request("GET", API + "/screenings/1/seats")
    if status != 200:
        raise RuntimeError(f"seat API: {status} {seats}")
    available = [seat["seatNo"] for seat in seats["seats"] if seat["status"] == "AVAILABLE"]
    if len(available) < args.payments:
        raise RuntimeError(f"Need {args.payments} free seats; found {len(available)}")
    started = datetime.now(timezone.utc)
    results = []
    for ordinal, seat in enumerate(available[:args.payments], 1):
        results.append(run_one(seat, ordinal, args.callers))
        if ordinal % 10 == 0:
            print(f"{ordinal}/{args.payments} payments checked", flush=True)
    db_duplicates = mysql("SELECT payment_id, COUNT(*) FROM reservations GROUP BY payment_id HAVING COUNT(*) > 1")
    report = {"startedAt": started.isoformat(), "finishedAt": datetime.now(timezone.utc).isoformat(),
              "payments": args.payments, "callersPerPayment": args.callers,
              "passed": sum(item["passed"] for item in results), "dbDuplicateRows": db_duplicates.splitlines(),
              "results": results}
    path = ROOT / "docs/evidence/payment-confirm" / (started.strftime("%Y%m%dT%H%M%SZ") + "-race.json")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(path)
    if report["passed"] != args.payments or db_duplicates:
        raise SystemExit("Payment confirmation race failed; inspect evidence")


if __name__ == "__main__":
    main()
