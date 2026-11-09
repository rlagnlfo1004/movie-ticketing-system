# Measurement capture

Window: 1794257066.368599 to 1794257696.881166 (Unix seconds).

`dashboard.png`, `panels/`, `prometheus/`, before/after MySQL and Redis snapshots, counter deltas and `summary.json` are the raw evidence. AWS runs also include `prometheus/host-cpu.json` and `prometheus/waiting-listen.json`.

No series in this run: InnoDB row lock waits, InnoDB row lock wait time, InnoDB deadlocks, MySQL Threads_running, MySQL commands, Redis AOF delayed fsync, Redis stream pending, Redis stream lag, Webhook response p95.

## 단계별로 달라진 설정

원본: [infra/stages/v3.env](../../../../infra/stages/v3.env)

| 설정 | 값 |
| --- | --- |
| STAGE_CODE_TAG | `v3-pubsub` |
| REDIS_CONFIG | `./infra/tuning/redis-v1.conf` |
| REDIS_APPENDONLY | `no` |
| REDIS_APPENDFSYNC | `everysec` |
| HOLD_DURATION | `30s` |
| PAYMENT_MODE | `mock` |
| ACTIVE_CHECK_MODE | `http` |
| SPRING_THREADS_VIRTUAL_ENABLED | `true` |

## worker 중단·유실 결과

- [중단 중](../../../../docs/evidence/v3-pubsub/20261109T174849Z-worker-kill-retry-204103-worker-during-outage.json): Redis 확정 584석, MySQL 0석, PG 승인 뒤 예매 없음 294건.
- [재기동 직후](../../../../docs/evidence/v3-pubsub/20261109T174849Z-worker-kill-retry-204103-worker-after-restart.json): Redis 584석, MySQL 0석, 누락 584석.
- [최종](../../../../docs/evidence/v3-pubsub/20261109T174849Z-worker-kill-retry-204103-after.json): Redis 782석, MySQL 198석, 누락 584석, PG 승인 뒤 예매 없음 294건.
