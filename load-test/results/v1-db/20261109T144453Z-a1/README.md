# Measurement capture

Window: 1794235673.402554 to 1794235699.7735438 (Unix seconds).

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

정식 단계 비교에서 제외한다. 30,001회는 시작됐지만 대기열 등록 오류율이 6.29%였고, 상태 조회의 502 응답이 대부분의 흐름을 좌석 단계 전에 중단했다. 원본은 `summary.json`과 `prometheus/k6-holds.json`에 있다. 원격 Nginx 로그에서 세 waiting-api upstream이 일시적으로 모두 제외됐고, waiting-api 호스트의 TCP listen overflow 증가를 확인했다. 호스트 backlog와 Tomcat accept·연결 한도를 높인 뒤 같은 조건으로 재측정했다.
