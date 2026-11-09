# Measurement capture

Window: 1794255991.364843 to 1794256623.174293 (Unix seconds).

`dashboard.png`, `panels/`, `prometheus/`, before/after MySQL and Redis snapshots, counter deltas and `summary.json` are the raw evidence. AWS runs also include `prometheus/host-cpu.json` and `prometheus/waiting-listen.json`.

No series in this run: InnoDB row lock waits, InnoDB row lock wait time, InnoDB deadlocks, MySQL Threads_running, MySQL commands, Redis AOF delayed fsync.

## 단계별로 달라진 설정

원본: [infra/stages/v4.env](../../../../infra/stages/v4.env)

| 설정 | 값 |
| --- | --- |
| STAGE_CODE_TAG | `v4-streams` |
| REDIS_CONFIG | `./infra/tuning/redis-v1.conf` |
| REDIS_APPENDONLY | `no` |
| REDIS_APPENDFSYNC | `everysec` |
| HOLD_DURATION | `30s` |
| PAYMENT_MODE | `mock` |
| ACTIVE_CHECK_MODE | `http` |
| SPRING_THREADS_VIRTUAL_ENABLED | `true` |

## worker 중단·회수 결과

- [중단 중](../../../../docs/evidence/v4-streams/20261109T181840Z-worker-kill-retry-202308-stream-during-outage.json): Redis 확정 382석, MySQL 0석, Stream lag 194건, PG 승인 뒤 예매 없음 194건.
- [재기동 뒤](../../../../docs/evidence/v4-streams/20261109T181840Z-worker-kill-retry-202308-stream-after-recovery.json): Redis 382석 = MySQL 382석, Stream lag 0건, PG 승인 뒤 예매 없음 0건.
- [최종](../../../../docs/evidence/v4-streams/20261109T181840Z-worker-kill-retry-202308-after.json): Redis 686석 = MySQL 686석, 누락 0석.
