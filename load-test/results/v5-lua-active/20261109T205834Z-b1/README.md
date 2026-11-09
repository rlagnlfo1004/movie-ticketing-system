# Measurement capture

Window: 1794258129.979697 to 1794258496.524745 (Unix seconds).

`dashboard.png`, `panels/`, `prometheus/`, before/after MySQL and Redis snapshots, counter deltas and `summary.json` are the raw evidence. AWS runs also include `prometheus/host-cpu.json` and `prometheus/waiting-listen.json`.

No series in this run: InnoDB row lock waits, InnoDB row lock wait time, InnoDB deadlocks, MySQL Threads_running, MySQL commands, Redis AOF delayed fsync, Webhook response p95.

## 단계별로 달라진 설정

원본: [infra/stages/v5.env](../../../../infra/stages/v5.env)

| 설정 | 값 |
| --- | --- |
| STAGE_CODE_TAG | `v5-lua-active` |
| REDIS_CONFIG | `./infra/tuning/redis-v1.conf` |
| REDIS_APPENDONLY | `no` |
| REDIS_APPENDFSYNC | `everysec` |
| HOLD_DURATION | `30s` |
| PAYMENT_MODE | `mock` |
| ACTIVE_CHECK_MODE | `lua` |
| SPRING_THREADS_VIRTUAL_ENABLED | `true` |
