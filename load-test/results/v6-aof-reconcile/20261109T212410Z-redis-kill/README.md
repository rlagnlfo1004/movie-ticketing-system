# Measurement capture

Window: 1794263292.7048361 to 1794263924.230968 (Unix seconds).

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

## 장애 검증 범위

Redis SIGKILL 직전 확정 좌석이 0석인 시점이었다. 최종 결제·좌석 균형은 확인됐지만, 이 실행만으로 저장된 확정 데이터의 AOF 복구를 증명하지 않는다. 확정 좌석이 생긴 뒤 Redis를 종료하는 단일 장애 재검증 결과를 공식 복구 근거로 사용한다.
