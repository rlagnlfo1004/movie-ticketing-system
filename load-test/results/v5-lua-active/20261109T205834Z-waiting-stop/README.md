# Measurement capture

Window: 1794258854.2749069 to 1794259220.8215292 (Unix seconds).

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

## waiting-api 중단 중 선점

- [중단 전](../../../../docs/evidence/v5-lua-active/20261109T205834Z-waiting-stop-waiting-down-before.json): 선점 키 550개.
- [중단 중](../../../../docs/evidence/v5-lua-active/20261109T205834Z-waiting-stop-waiting-down-after.json): 선점 키 715개.
- [k6 성공·시도 카운터](prometheus/k6-holds.json): 중단 창에 선점 성공 411건 / 시도 3,995건(10.29%). 좌석 충돌도 시도 분모에 포함한다.
