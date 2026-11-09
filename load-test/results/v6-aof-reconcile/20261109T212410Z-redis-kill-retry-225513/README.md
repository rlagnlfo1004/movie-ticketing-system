# Measurement capture

Window: 1794265115.85887 to 1794265748.6262138 (Unix seconds).

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

## 확정 데이터가 있는 Redis 재시작 결과

- [종료 직전](../../../../docs/evidence/v6-aof-reconcile/20261109T212410Z-redis-kill-retry-225513-redis-before-kill.json): Redis 확정 좌석 189석이 있는 상태에서 SIGKILL했다. 부하 중 Redis·MySQL 조회 시각은 서로 다르므로 직전 좌석 수 차이를 유실로 해석하지 않는다.
- [재시작 뒤](../../../../docs/evidence/v6-aof-reconcile/20261109T212410Z-redis-kill-retry-225513-redis-after-restart.json): Redis 452석 = MySQL 452석, PG 승인 222건 = 확정 222건.
- [최종 균형](../../../../docs/evidence/v6-aof-reconcile/20261109T212410Z-redis-kill-retry-225513-final-balance.json): Redis 693석 = MySQL 693석, PG 승인 365건 = 확정 365건, 좌석 누락 0석. 대사의 복원·취소 건수는 0건이므로 이번 실행은 AOF 재시작 뒤 일치 유지를 보여 준다.
