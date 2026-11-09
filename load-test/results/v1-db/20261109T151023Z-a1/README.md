# Measurement capture

Window: 1794237213.848469 to 1794237845.810551 (Unix seconds).

`dashboard.png`, `panels/`, `prometheus/`, before/after MySQL and Redis snapshots, counter deltas and `summary.json` are the raw evidence. AWS runs also include `prometheus/host-cpu.json` and `prometheus/waiting-listen.json`.

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

정식 단계 비교에서 제외한다. k6 summary와 Prometheus JSON은 저장됐지만 Grafana PNG가 `No data`다. 예를 들어 `prometheus/26.json`에는 124개 시계열 표본이 있으나 `panels/26.png`는 빈 그래프다. 원격 Grafana 데이터소스가 Docker DNS 주소 `prometheus:9090`을 바라본 배포 설정 오류였다. 원격 host 네트워크 주소 `127.0.0.1:9090`으로 수정하고 실행 전 Grafana 경유 조회를 검증한다.
