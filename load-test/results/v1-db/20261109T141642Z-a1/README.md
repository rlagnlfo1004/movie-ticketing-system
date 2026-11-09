# Measurement capture

Window: 1794233994.1258721 to 1794234091.005661 (Unix seconds).

`dashboard.png`, `panels/`, `prometheus/`, before/after MySQL and Redis snapshots, counter deltas and `summary.json` are the raw evidence.

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

## 유효성 판단

정식 단계 비교에서 제외한다. k6가 실행 중 VU를 늘리는 동안 예정된 30,000회 중 16,980회를 시작하지 못했다. 원본 `summary.json`의 `dropped_iterations`와 `iterations`로 확인한다. 부하 발생기를 c7g.2xlarge로 확장하고 비스모크 A의 VU 30,000개를 사전 할당했다.
