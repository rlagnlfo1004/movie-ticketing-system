# Measurement capture

Window: 1794236173.491776 to 1794236805.1004748 (Unix seconds).

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

정식 단계 비교에서 제외한다. 30,001회 실행은 시작됐지만 대기열 등록 오류 3205건이 발생했고 좌석 선점은 1건에 그쳤다. 원본은 `summary.json`과 `prometheus/k6-holds.json`에 있다. 호스트 원본 `prometheus/queue-burst-host.json`에서 TCP listen overflow 증가 0건을 확인했지만, waiting-api CPU는 최고 93.8%까지 올라갔다. 대기열 상태를 1초마다 조회해 응답 시간 초과가 이어졌다. 다음 실행은 대기 중 조회 간격 5초와 시작 시각 분산·지터를 적용한다.
