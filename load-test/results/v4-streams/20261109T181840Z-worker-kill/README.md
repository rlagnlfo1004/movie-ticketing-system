# Measurement capture

Window: 1794252162.6751392 to 1794252794.0567548 (Unix seconds).

`dashboard.png`, `panels/`, `prometheus/`, before/after MySQL and Redis snapshots, counter deltas and `summary.json` are the raw evidence. AWS runs also include `prometheus/host-cpu.json` and `prometheus/waiting-listen.json`.

No series in this run: InnoDB row lock waits, InnoDB row lock wait time, InnoDB deadlocks, MySQL Threads_running, MySQL commands, Redis AOF delayed fsync.

## 단계별로 달라진 설정

원본: [infra/stages/v4.env](../../../../infra/stages/v4.env)

| 설정 | 값 |
| --- | --- |
| STAGE_CODE_TAG | `v4-streams` |
| REDIS_CONFIG | `./infra/tuning/redis-v1.conf` |
| REDIS_APPENDONLY | `no` |
| REDIS_APPENDFSYNC | `everysec` |
| HOLD_DURATION | `30s` |
| PAYMENT_MODE | `mock` |
| ACTIVE_CHECK_MODE | `http` |
| SPRING_THREADS_VIRTUAL_ENABLED | `true` |

## 장애 검증 범위

worker 종료 직전과 직후 스냅샷에는 확정 이벤트가 아직 0건이었다. 재기동 후 Redis·MySQL 차이가 0건인 결과는 남기지만, 이 실행만으로 PEL 미확인 항목 회수를 증명하지 않는다. 확정 이벤트가 쌓인 뒤 worker를 30초 중단하는 단일 장애 재검증 결과를 공식 회수 근거로 사용한다.
