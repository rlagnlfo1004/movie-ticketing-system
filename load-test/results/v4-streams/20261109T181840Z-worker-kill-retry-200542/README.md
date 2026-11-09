# Measurement capture

Window: 1794254944.216137 to 1794255576.655767 (Unix seconds).

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

확정 좌석 20석 이후 worker를 종료했지만 중단 중 Stream lag·pending이 0이었다. 처리 중 이벤트를 막은 근거가 없어 이 실행도 회수 성공 사례로 쓰지 않는다. 다음 재검증은 부하 시작 5초 뒤 바로 종료하고 30초 중단 중 backlog 증가를 완료 조건으로 검사한다.
