# Measurement capture

Window: 1794261597.353772 to 1794262228.175631 (Unix seconds).

`dashboard.png`, `panels/`, `prometheus/`, before/after MySQL and Redis snapshots, counter deltas and `summary.json` are the raw evidence. AWS runs also include `prometheus/host-cpu.json` and `prometheus/waiting-listen.json`.

No series in this run: InnoDB row lock waits, InnoDB row lock wait time, InnoDB deadlocks, MySQL Threads_running, MySQL commands.

## 단계별로 달라진 설정

원본: [infra/stages/v6.env](../../../../infra/stages/v6.env)

| 설정 | 값 |
| --- | --- |
| STAGE_CODE_TAG | `v6-aof-reconcile` |
| REDIS_CONFIG | `./infra/tuning/redis-v6.conf` |
| REDIS_APPENDONLY | `yes` |
| REDIS_APPENDFSYNC | `everysec` |
| HOLD_DURATION | `30s` |
| PAYMENT_MODE | `mock` |
| ACTIVE_CHECK_MODE | `lua` |
| SPRING_THREADS_VIRTUAL_ENABLED | `true` |
