# Measurement capture

Window: 1794239348.7006469 to 1794239980.074382 (Unix seconds).

`dashboard.png`, `panels/`, `prometheus/`, before/after MySQL and Redis snapshots, counter deltas and `summary.json` are the raw evidence. AWS runs also include `prometheus/host-cpu.json` and `prometheus/waiting-listen.json`.

No series in this run: InnoDB row lock waits, InnoDB row lock wait time, InnoDB deadlocks, MySQL Threads_running, MySQL commands, Redis AOF delayed fsync, Redis stream pending, Redis stream lag, Webhook response p95.

## 단계별로 달라진 설정

원본: [infra/stages/v1.env](../../../../infra/stages/v1.env)

| 설정 | 값 |
| --- | --- |
| STAGE_CODE_TAG | `v1-db` |
| REDIS_CONFIG | `./infra/tuning/redis-v1.conf` |
| REDIS_APPENDONLY | `no` |
| REDIS_APPENDFSYNC | `everysec` |
| HOLD_DURATION | `30s` |
| PAYMENT_MODE | `mock` |
| ACTIVE_CHECK_MODE | `http` |
| SPRING_THREADS_VIRTUAL_ENABLED | `true` |
