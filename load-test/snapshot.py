#!/usr/bin/env python3
"""Write a moment-in-time V1 DB/PG evidence pair (Markdown and raw JSON)."""
import json
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]


def mysql(sql):
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "-e", "MYSQL_PWD=root", "mysql", "mysql", "-uroot", "-N", "-B", "movie_seats", "-e", sql],
        cwd=ROOT, capture_output=True, text=True, check=True,
    )
    return result.stdout


def rows(sql):
    return [json.loads(line) for line in mysql(sql).splitlines() if line]


def main(stage, moment):
    locks_sql = """
        SELECT JSON_OBJECT('transactionId',ENGINE_TRANSACTION_ID,'table',OBJECT_NAME,
          'index',INDEX_NAME,'type',LOCK_TYPE,'mode',LOCK_MODE,'status',LOCK_STATUS,'data',LOCK_DATA)
        FROM performance_schema.data_locks WHERE OBJECT_SCHEMA='movie_seats'
    """
    waits_sql = """
        SELECT JSON_OBJECT('requesting',REQUESTING_ENGINE_TRANSACTION_ID,
          'blocking',BLOCKING_ENGINE_TRANSACTION_ID,'requestingLock',REQUESTING_ENGINE_LOCK_ID,
          'blockingLock',BLOCKING_ENGINE_LOCK_ID)
        FROM performance_schema.data_lock_waits
    """
    missing_sql = """
        SELECT JSON_OBJECT('paymentId',p.payment_id,'localStatus',p.status,'amount',p.amount)
        FROM payments p LEFT JOIN reservations r ON r.payment_id=p.payment_id
        WHERE p.status='PAID' AND r.reservation_id IS NULL
    """
    local_sql = """
        SELECT JSON_OBJECT('paymentId',p.payment_id,'localStatus',p.status,'amount',p.amount)
        FROM payments p LEFT JOIN reservations r ON r.payment_id=p.payment_id
        WHERE r.reservation_id IS NULL
    """
    innodb = mysql("SHOW ENGINE INNODB STATUS\\G")
    marker = "LATEST DETECTED DEADLOCK"
    deadlock = innodb.split(marker, 1)[1].split("TRANSACTIONS", 1)[0].strip("-\n ") if marker in innodb else ""
    with urlopen("http://localhost:8082/test/payments", timeout=5) as response:
        pg = json.load(response)["payments"]
    local_unconfirmed = rows(local_sql)
    local_by_id = {item["paymentId"]: item for item in local_unconfirmed}
    pg_missing = [
        {"paymentId": item["id"], "pgStatus": item["status"],
         "localStatus": local_by_id.get(item["id"], {}).get("localStatus", "NO_LOCAL_PAYMENT")}
        for item in pg if item["status"] == "PAID" and item["id"] in local_by_id
    ]
    data = {
        "capturedAt": datetime.now(timezone.utc).isoformat(), "moment": moment,
        "queries": {"locks": locks_sql.strip(), "waits": waits_sql.strip(),
                    "paidWithoutReservation": missing_sql.strip(), "unconfirmedLocal": local_sql.strip(),
                    "deadlock": "SHOW ENGINE INNODB STATUS\\G", "pg": "GET /test/payments"},
        "locks": rows(locks_sql), "waits": rows(waits_sql), "latestDeadlock": deadlock,
        "paidWithoutReservation": rows(missing_sql), "pgPaidWithoutReservation": pg_missing,
    }
    directory = ROOT / "docs/evidence" / stage
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{moment}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    interpretation = {
        "hold-contention": "좌석 선점 중 락 보유·대기 관계를 확인한다.",
        "deadlock-row": "요청 좌석 순서 역전으로 발생한 deadlock을 확인한다.",
        "deadlock-fk": "상영 FK S-lock 뒤 X-lock 전환 경로의 deadlock을 확인한다.",
        "app-closed": "V1에서 PG는 PAID이나 로컬 결제는 READY이며 예매가 없는 건을 확인한다.",
    }.get(moment, "상태 스냅샷을 확인한다.")
    markdown = f"""# {stage}: {moment}

- 시각: {data['capturedAt']}
- 실행한 쿼리·명령: 같은 이름의 JSON 파일 `queries`에 원문을 보존했다.

| 항목 | 결과 |
| --- | ---: |
| 좌석·상영 락 | {len(data['locks'])}건 |
| 락 대기 관계 | {len(data['waits'])}건 |
| 최근 deadlock 기록 | {'있음' if deadlock else '없음'} |
| DB PAID·예매 없음 | {len(data['paidWithoutReservation'])}건 |
| PG PAID·로컬 예매 없음 | {len(pg_missing)}건 |

해석: {interpretation}
"""
    (directory / f"{moment}.md").write_text(markdown)
    print(directory / f"{moment}.md")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: snapshot.py <stage> <moment>")
    main(sys.argv[1], sys.argv[2])
