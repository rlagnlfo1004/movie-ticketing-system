# Measurement capture

Window: 1794234640.84536 to 1794235273.485666 (Unix seconds).

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

정식 단계 비교에서 제외한다. 30,001회 실행은 시작됐지만 Nginx 양쪽 worker가 `10240 worker_connections are not enough`를 기록했다. `summary.json`의 대기열 등록 오류율은 43.12%였고, Prometheus의 k6 HTTP 상태 원본에는 코드 0 연결 실패가 나타난다. worker당 연결 한도를 65,535로 올려 재측정했다.
