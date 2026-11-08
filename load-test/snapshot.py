#!/usr/bin/env python3
"""Write a moment-in-time V1 DB/PG evidence pair (Markdown and raw JSON)."""
import json
import os
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone
from urllib.request import urlopen
from urllib.parse import urlencode
from local_env import get as local_env
from remote_compose import route

ROOT = Path(__file__).resolve().parents[1]
MYSQL_ROOT_PASSWORD = os.environ.get("SEAT_DB_ROOT_PASSWORD") or local_env("SEAT_DB_ROOT_PASSWORD")


def mysql(sql):
    result = subprocess.run(
        route(["docker", "compose", "exec", "-T", "-e", f"MYSQL_PWD={MYSQL_ROOT_PASSWORD}", "mysql", "mysql", "-uroot", "-N", "-B", "movie_seats", "-e", sql]),
        cwd=ROOT, capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        if os.environ.get("MYSQL_UNAVAILABLE") == "1":
            return ""
        raise RuntimeError(result.stderr)
    return result.stdout


def rows(sql):
    return [json.loads(line) for line in mysql(sql).splitlines() if line]


def redis_json(*command):
    result = subprocess.run(route(["docker", "compose", "exec", "-T", "redis", "redis-cli", "--json", *command]),
                            cwd=ROOT, capture_output=True, text=True, check=False)
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return None


def redis_scan_count(pattern):
    result = subprocess.run(route(["docker", "compose", "exec", "-T", "redis", "redis-cli", "--scan", "--pattern", pattern]),
                            cwd=ROOT, capture_output=True, text=True, check=True)
    return len([line for line in result.stdout.splitlines() if line.strip()])


def redis_info(section):
    result = subprocess.run(route(["docker", "compose", "exec", "-T", "redis", "redis-cli", "INFO", section]),
                            cwd=ROOT, capture_output=True, text=True, check=True)
    return dict(line.strip().split(":", 1) for line in result.stdout.splitlines()
                if ":" in line and not line.startswith("#"))


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
    recent_sql = """
        SELECT JSON_OBJECT('paymentId',payment_id,'status',status,'amount',amount)
        FROM payments ORDER BY updated_at DESC LIMIT 30
    """
    innodb_counters_sql = """
        SELECT JSON_OBJECT('name',name,'count',count)
        FROM information_schema.innodb_metrics WHERE name LIKE 'lock_row_lock_%'
    """
    innodb = mysql("SHOW ENGINE INNODB STATUS\\G")
    marker = "LATEST DETECTED DEADLOCK"
    deadlock = innodb.split(marker, 1)[1].split("TRANSACTIONS", 1)[0].strip("-\n ") if marker in innodb else ""
    with urlopen(os.environ.get("MOCK_PG_URL", "http://localhost:8082") + "/test/payments", timeout=5) as response:
        pg = json.load(response)["payments"]
    mysql_available = os.environ.get("MYSQL_UNAVAILABLE") != "1"
    deadlock_count = int(mysql("SELECT count FROM information_schema.innodb_metrics WHERE name='lock_deadlocks'").strip() or 0)
    try:
        query = urlencode({"query": "hikaricp_connections_pending"})
        with urlopen(f"{os.environ.get('PROMETHEUS_URL', 'http://localhost:9090')}/api/v1/query?{query}", timeout=5) as response:
            hikari_pending = json.load(response).get("data", {}).get("result", [])
    except Exception:
        hikari_pending = []
    try:
        with urlopen(os.environ.get("RESERVATION_API_URL", "http://localhost:8081") + "/actuator/metrics/hikaricp.connections.pending", timeout=5) as response:
            hikari_pending_live = json.load(response)
    except Exception:
        hikari_pending_live = {}
    local_unconfirmed = rows(local_sql)
    redis_reserved_output = subprocess.run(
        route(["docker", "compose", "exec", "-T", "redis", "redis-cli", "--raw", "SMEMBERS", "seat:{1}:reserved"]),
        cwd=ROOT, capture_output=True, text=True, check=True).stdout
    redis_reserved = sorted(line.strip() for line in redis_reserved_output.splitlines() if line.strip())
    active_key_count = redis_scan_count("waiting:screening:1:active:*")
    seat_hold_key_count = redis_scan_count("seat:{1}:hold:*")
    db_reserved = sorted(line.strip() for line in mysql("SELECT seat_no FROM reservation_seats WHERE screening_id='1'").splitlines() if line.strip())
    local_by_id = {item["paymentId"]: item for item in local_unconfirmed}
    confirmed_payment_ids = set(mysql("SELECT payment_id FROM reservations").splitlines())
    approved_pg_ids = {item["id"] for item in pg if item.get("paidAt")}
    confirmed_pg_ids = sorted(approved_pg_ids & confirmed_payment_ids) if mysql_available else None
    cancelled_pg_ids = sorted(item["id"] for item in pg if item.get("paidAt") and item["status"] == "CANCELLED")
    unresolved_pg_ids = sorted(approved_pg_ids - set(confirmed_pg_ids or []) - set(cancelled_pg_ids)) if mysql_available else None
    pg_missing = [
        {"paymentId": item["id"], "pgStatus": item["status"],
         "localStatus": local_by_id.get(item["id"], {}).get("localStatus", "NO_LOCAL_PAYMENT")}
        for item in pg if mysql_available and item["status"] == "PAID" and item["id"] not in confirmed_payment_ids
    ]
    stream = "stream:{1}:reservations"
    stream_length = redis_json("XLEN", stream)
    group_info = redis_json("XINFO", "GROUPS", stream) or []
    pending_info = redis_json("XPENDING", stream, "workers") or [0]
    dlq = redis_json("XRANGE", stream + ":dlq", "-", "+", "COUNT", "10") or []
    redis_aof_config = redis_json("CONFIG", "GET", "appendonly", "appendfsync") or {}
    redis_persistence = redis_info("persistence")
    try:
        with urlopen(os.environ.get("RESERVATION_API_URL", "http://localhost:8081") + "/admin/reconcile", timeout=5) as response:
            reconciliation_result = json.load(response)
    except Exception:
        reconciliation_result = None
    data = {
        "capturedAt": datetime.now(timezone.utc).isoformat(), "moment": moment,
        "queries": {"locks": locks_sql.strip(), "waits": waits_sql.strip(),
                    "paidWithoutReservation": missing_sql.strip(), "unconfirmedLocal": local_sql.strip(),
                    "deadlock": "SHOW ENGINE INNODB STATUS\\G", "pg": "GET /test/payments",
                    "deadlockCount": "SELECT count FROM information_schema.innodb_metrics WHERE name='lock_deadlocks'",
                    "innodbCounters": innodb_counters_sql.strip(), "hikariPending": "hikaricp_connections_pending",
                    "hikariPendingLive": "GET /actuator/metrics/hikaricp.connections.pending",
                    "recentPayments": recent_sql.strip()},
        "locks": rows(locks_sql) if mysql_available else None,
        "waits": rows(waits_sql) if mysql_available else None,
        "latestDeadlock": deadlock if mysql_available else None,
        "deadlockCount": deadlock_count if mysql_available else None,
        "innodbCounters": rows(innodb_counters_sql) if mysql_available else None,
        "hikariPending": hikari_pending, "hikariPendingLive": hikari_pending_live,
        "recentPayments": rows(recent_sql) if mysql_available else None,
        "paidWithoutReservation": rows(missing_sql) if mysql_available else None,
        "pgPaidWithoutReservation": pg_missing if mysql_available else None,
        "redisReservedSeats": redis_reserved,
        "activeKeyCount": active_key_count, "seatHoldKeyCount": seat_hold_key_count,
        "mysqlReservationSeats": db_reserved if mysql_available else None,
        "missingMysqlSeats": sorted(set(redis_reserved) - set(db_reserved)) if mysql_available else None,
        "mysqlAvailable": mysql_available, "streamLength": stream_length,
        "streamGroups": group_info, "streamPending": pending_info, "deadLetterEntries": dlq,
        "redisAofConfig": redis_aof_config, "redisPersistence": redis_persistence,
        "reconciliationResult": reconciliation_result,
        "approvedPgPayments": sorted(approved_pg_ids),
        "confirmedPgPayments": confirmed_pg_ids,
        "cancelledPgPayments": cancelled_pg_ids,
        "unresolvedPgPayments": unresolved_pg_ids,
    }
    directory = ROOT / "docs/evidence" / stage
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{moment}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    descriptions = {
        "hold-contention": "좌석 선점 중 락 보유·대기 관계를 확인한다.",
        "deadlock-row": "요청 좌석 순서 역전으로 발생한 deadlock을 확인한다.",
        "deadlock-fk": "상영 FK S-lock 뒤 X-lock 전환 경로의 deadlock을 확인한다.",
        "app-closed": "V1에서 PG는 PAID이나 로컬 결제는 READY이며 예매가 없는 건을 확인한다.",
        "v2-baseline": "V2 동시 요청 전의 deadlock 누적값과 결제 상태를 기록한다.",
        "v2-after-locks": "V2 동시 요청 뒤 deadlock 누적값과 상영 행 대기를 비교한다.",
        "throughput-ceiling": "상영 행 직렬화 중의 DB 락 대기와 Hikari 대기를 확인한다.",
        "late-payment-before-cancel": "만료된 선점의 결제 전 상태를 기록한다.",
        "late-payment-after-cancel": "좌석이 재선점된 뒤 도착한 결제가 취소됐는지 기록한다.",
        "worker-before-kill": "worker 종료 전 Redis 확정 좌석과 MySQL 적재 좌석의 차이를 기준값으로 기록한다.",
        "worker-after-kill": "worker 종료 중 Redis에서 확정됐으나 MySQL에 적재되지 않은 좌석을 기록한다.",
        "worker-after-restart": "worker를 다시 띄워도 Pub/Sub 이벤트가 재전달되지 않는지 확인한다.",
        "stream-before-kill": "worker 종료 직전 미확인 Stream 항목을 기록한다.",
        "stream-after-kill": "worker 종료 뒤 PEL의 미확인 항목을 기록한다.",
        "stream-after-recovery": "XAUTOCLAIM과 DB 커밋 뒤 PEL이 비었는지 확인한다.",
        "mysql-down": "MySQL 중단 중 Redis Stream 길이와 lag를 기록한다.",
        "mysql-recovered": "MySQL 재개 뒤 Stream lag와 DB 적재 상태를 기록한다.",
        "redis-before-kill": "Redis 강제 종료 전 AOF 설정과 확정 좌석을 기록한다.",
        "redis-after-restart": "AOF 재시작 직후 Redis·MySQL 좌석과 PG 결제를 기록한다.",
        "redis-after-reconcile": "대사 뒤 좌석·결제 균형과 확정·취소 목록을 확인한다.",
        "pg-recovered-free": "PG 승인 결제의 좌석이 비어 있어 대사가 확정한 목록을 기록한다.",
        "pg-cancelled-taken": "PG 승인 결제의 좌석이 재선점돼 대사가 취소한 목록을 기록한다.",
        "pg-stream-fallback": "PG 컨텍스트가 없는 이전 단계 결제를 남아 있는 Stream 이벤트로 복원한다.",
        "waiting-down-before": "waiting-api 전체 중단 중 이미 발급된 active 키 수를 기록한다.",
        "waiting-down-after": "waiting-api 전체 중단 중 Redis Lua가 성공시킨 선점을 확인한다.",
        "stream-duplicate-acked": "중복 Stream 항목이 DB 좌석 수를 다시 줄이지 않고 ACK됐는지 확인한다.",
        "dlq-after-retries": "재전달 한도를 넘긴 Stream 항목의 DLQ 이동을 확인한다.",
    }
    interpretation = next((value for key, value in descriptions.items()
                           if moment == key or moment.endswith("-" + key)),
                          "상태 스냅샷을 확인한다.")
    unavailable = "조회 불가"
    markdown = f"""# {stage}: {moment}

- 시각: {data['capturedAt']}
- 실행한 쿼리·명령: 같은 이름의 JSON 파일 `queries`에 원문을 보존했다.
- InnoDB의 최근 deadlock 절은 이전 단계 기록도 유지된다. 이번 단계의 신규 건수는 전후 누적값 차이로 판정한다.

| 항목 | 결과 |
| --- | ---: |
| 좌석·상영 락 | {str(len(data['locks'])) + '건' if mysql_available else unavailable} |
| 락 대기 관계 | {str(len(data['waits'])) + '건' if mysql_available else unavailable} |
| 최근 deadlock 기록 | {('있음' if deadlock else '없음') if mysql_available else unavailable} |
| deadlock 누적값 | {str(deadlock_count) + '건' if mysql_available else unavailable} |
| InnoDB row lock 계측 | {str(len(data['innodbCounters'])) + '개' if mysql_available else unavailable} |
| Hikari pending 시계열 | {len(hikari_pending)}개 |
| Hikari pending 즉시값 | {hikari_pending_live.get('measurements', [{}])[0].get('value', '없음')} |
| DB PAID·예매 없음 | {str(len(data['paidWithoutReservation'])) + '건' if mysql_available else unavailable} |
| PG PAID·로컬 예매 없음 | {str(len(pg_missing)) + '건' if mysql_available else unavailable} |
| Redis 확정 좌석 | {len(redis_reserved)}석 |
| active 키 | {active_key_count}개 |
| 좌석 선점 키 | {seat_hold_key_count}개 |
| MySQL 적재 좌석 | {str(len(db_reserved)) + '석' if mysql_available else unavailable} |
| Redis에만 있는 좌석 | {str(len(data['missingMysqlSeats'])) + '석' if mysql_available else unavailable} |
| MySQL 조회 가능 | {'예' if mysql_available else '아니오'} |
| Stream 길이 | {stream_length if stream_length is not None else '없음'}건 |
| Stream pending | {pending_info[0] if pending_info else '없음'}건 |
| Stream lag | {group_info[0].get('lag', '없음') if group_info else '없음'}건 |
| DLQ 최근 항목 | {len(dlq)}건 |
| Redis appendonly | {redis_aof_config.get('appendonly', '없음')} |
| Redis appendfsync | {redis_aof_config.get('appendfsync', '없음')} |
| AOF delayed fsync | {redis_persistence.get('aof_delayed_fsync', '없음')}건 |
| PG 승인 건 | {len(approved_pg_ids)}건 |
| PG 승인 중 DB 확정 | {str(len(confirmed_pg_ids)) + '건' if mysql_available else unavailable} |
| PG 승인 중 취소 | {len(cancelled_pg_ids)}건 |
| PG 승인 중 미해소 | {str(len(unresolved_pg_ids)) + '건' if mysql_available else unavailable} |
| 이번 대사 확정 결제 | {len((reconciliation_result or {}).get('confirmedPayments', []))}건 |
| 이번 대사 취소 결제 | {len((reconciliation_result or {}).get('cancelledPayments', []))}건 |

해석: {interpretation}
"""
    (directory / f"{moment}.md").write_text(markdown)
    print(directory / f"{moment}.md")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: snapshot.py <stage> <moment>")
    main(sys.argv[1], sys.argv[2])
