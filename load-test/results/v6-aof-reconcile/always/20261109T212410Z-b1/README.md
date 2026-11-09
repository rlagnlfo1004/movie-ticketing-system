# Measurement capture

Window: 1794264323.006711 to 1794264689.55995 (Unix seconds).

`dashboard.png`, `panels/`, `prometheus/`, before/after MySQL and Redis snapshots, counter deltas and `summary.json` are the raw evidence. AWS runs also include `prometheus/host-cpu.json` and `prometheus/waiting-listen.json`.

No series in this run: InnoDB row lock waits, InnoDB row lock wait time, InnoDB deadlocks, MySQL Threads_running, MySQL commands, Webhook response p95.

## 단계별로 달라진 설정

원본: [infra/stages/v6-always.env](../../../../../infra/stages/v6-always.env)

| 설정 | 값 |
| --- | --- |
| STAGE_CODE_TAG | `v6-aof-reconcile` |
| REDIS_CONFIG | `./infra/tuning/redis-v6-always.conf` |
| REDIS_APPENDONLY | `yes` |
| REDIS_APPENDFSYNC | `always` |
| HOLD_DURATION | `30s` |
| PAYMENT_MODE | `mock` |
| ACTIVE_CHECK_MODE | `lua` |
| SPRING_THREADS_VIRTUAL_ENABLED | `true` |
