# 좌석 예매 측정 방법과 원본

문서 생성 시각(UTC): 2026-11-09T20:21:54.387818+00:00

## 환경

정식 측정은 AWS 서울 리전의 동일 AZ·서브넷에서 수행한다. 다음 표는 계획 사양이다. 실행 사양과 다르면 측정 당시 인스턴스 정보를 별도로 기록해야 한다.

| 호스트 | 인스턴스 | vCPU |
| --- | --- | ---: |
| lg | c7g.2xlarge | 8 |
| edge | c7g.large | 2 |
| app-waiting | m7g.xlarge | 4 |
| app-reservation | m7g.xlarge | 4 |
| redis | r7g.large | 2 |
| mysql | r7g.xlarge | 4 |
| mon | t4g.medium | 2 |

## 적용 튜닝

아래 값은 배포 묶음에 포함한 설정 파일에서 읽었다.

| 파일 | 설정 | 값 |
| --- | --- | --- |
| [sysctl.conf](../../infra/tuning/sysctl.conf) | `net.core.somaxconn` | `16384` |
| [sysctl.conf](../../infra/tuning/sysctl.conf) | `net.ipv4.tcp_max_syn_backlog` | `16384` |
| [sysctl.conf](../../infra/tuning/sysctl.conf) | `net.ipv4.ip_local_port_range` | `10240 65535` |
| [sysctl.conf](../../infra/tuning/sysctl.conf) | `net.ipv4.tcp_tw_reuse` | `1` |
| [sysctl.conf](../../infra/tuning/sysctl.conf) | `fs.file-max` | `2097152` |
| [limits.conf](../../infra/tuning/limits.conf) | `* soft nofile` | `65535` |
| [limits.conf](../../infra/tuning/limits.conf) | `* hard nofile` | `65535` |
| [mysql-stage.cnf](../../infra/tuning/mysql-stage.cnf) | `max_connections` | `500` |
| [mysql-stage.cnf](../../infra/tuning/mysql-stage.cnf) | `innodb_buffer_pool_size` | `22G` |
| [mysql-stage.cnf](../../infra/tuning/mysql-stage.cnf) | `innodb_redo_log_capacity` | `2G` |
| [mysql-stage.cnf](../../infra/tuning/mysql-stage.cnf) | `innodb_flush_log_at_trx_commit` | `1` |
| [mysql-stage.cnf](../../infra/tuning/mysql-stage.cnf) | `sync_binlog` | `1` |
| [mysql-stage.cnf](../../infra/tuning/mysql-stage.cnf) | `innodb_print_all_deadlocks` | `ON` |
| [jvm.env](../../infra/tuning/jvm.env) | `JAVA_TOOL_OPTIONS` | `-Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=50` |

## 절차

각 단계의 git 태그에서 JAR를 빌드한다. 각 실행 전에 MySQL·Redis 데이터를 초기화하고 2분 워밍업을 거친다. A 전체 흐름 3회는 V1·V2·V4·V6에 적용하고, B 선점 처리량 상한 1회는 V1~V6 모두 실행한다. 단계별 장애 실행과 추가 비교는 유지한다. 로컬 `--smoke` 검증은 워밍업 1초, B 구간당 1초로 줄인다.

- A: 상영 1개·1,000석·사용자 3만 명. 좌석 선택의 70%는 가운데 30%에 몰린다. 첫 대기 조회를 등록 뒤 2~10초에 분산하고, 대기 중 5초 권장 간격에 ±20% 지터를 준다. 선점 성공자의 85%가 결제하고, 결제자의 10%가 완료 API를 생략한다.
- B: 입장 토큰을 미리 시드한 뒤 초당 100·200·400·800·1,600·3,200건을 각각 60초 보낸다. 좌석 소진 뒤에는 충돌 응답 처리량이므로 성공 선점 TPS와 구분한다.
- 모의 PG 승인 지연 500~2,000ms·웹훅 지연 200~800ms·첫 발송 실패 1%는 실측값이 아닌 가정이다.

## 단계별 원본

### v1-db

- 실행 시작(UTC): 2026-11-09T15:30:03.490517+00:00
- 실행 명령: `./load-test/run-stage.sh v1`
- 성격: 정식 측정
- 코드: `v1-db`

| 설정 | 값 |
| --- | --- |
| REDIS_CONFIG | `./infra/tuning/redis-v1.conf` |
| REDIS_APPENDONLY | `no` |
| REDIS_APPENDFSYNC | `everysec` |
| HOLD_DURATION | `30s` |
| PAYMENT_MODE | `mock` |
| ACTIVE_CHECK_MODE | `http` |
| SPRING_THREADS_VIRTUAL_ENABLED | `true` |

| 실행 | 선점 성공 | 평균 성공 TPS | 선점 p95/p99(ms) | 확정 | deadlock 증가 | 웹훅 p95(s) | Stream lag 최대 |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: |
| [20261109T153003Z-a1](v1-db/20261109T153003Z-a1/README.md) | 270.00 | 0.43 | 46.43 / 68.77 | 199.00 | 85.0 | — | — |
| [20261109T153003Z-a2](v1-db/20261109T153003Z-a2/README.md) | 328.00 | 0.53 | 496.88 / 800.84 | 259.00 | 439.0 | — | — |
| [20261109T153003Z-a3](v1-db/20261109T153003Z-a3/README.md) | 366.00 | 0.59 | 128.04 / 225.81 | 291.00 | 304.0 | — | — |
| [20261109T153003Z-b1](v1-db/20261109T153003Z-b1/README.md) | 2940.00 | 8.09 | 3533.17 / 3753.85 | — | 172.0 | — | — |

실행별 원본:

- 20261109T153003Z-a1: [대시보드](v1-db/20261109T153003Z-a1/dashboard.png) · [패널 PNG](v1-db/20261109T153003Z-a1/panels/) · [Prometheus JSON](v1-db/20261109T153003Z-a1/prometheus/) · [k6 원본](v1-db/20261109T153003Z-a1/summary.json)
- 20261109T153003Z-a2: [대시보드](v1-db/20261109T153003Z-a2/dashboard.png) · [패널 PNG](v1-db/20261109T153003Z-a2/panels/) · [Prometheus JSON](v1-db/20261109T153003Z-a2/prometheus/) · [k6 원본](v1-db/20261109T153003Z-a2/summary.json)
- 20261109T153003Z-a3: [대시보드](v1-db/20261109T153003Z-a3/dashboard.png) · [패널 PNG](v1-db/20261109T153003Z-a3/panels/) · [Prometheus JSON](v1-db/20261109T153003Z-a3/prometheus/) · [k6 원본](v1-db/20261109T153003Z-a3/summary.json)
- 20261109T153003Z-b1: [대시보드](v1-db/20261109T153003Z-b1/dashboard.png) · [패널 PNG](v1-db/20261109T153003Z-b1/panels/) · [Prometheus JSON](v1-db/20261109T153003Z-b1/prometheus/) · [k6 원본](v1-db/20261109T153003Z-b1/summary.json)

장애 주입과 상태 스냅샷(UTC, 시간순):

- 2026-11-09T15:33:02.322650+00:00 · scenario-A · 종료 2026-11-09T15:43:33.541718+00:00 · 종료 코드 99
- 2026-11-09T15:45:56.021500+00:00 · app-closed · [스냅샷](../../docs/evidence/v1-db/20261109T153003Z-a1-app-closed.json) · Redis 확정 0석 / MySQL 449석 / PG 승인·예매 없음 26건
- 2026-11-09T15:49:08.700647+00:00 · scenario-A · 종료 2026-11-09T15:59:40.074382+00:00 · 종료 코드 99
- 2026-11-09T16:02:05.676350+00:00 · app-closed · [스냅샷](../../docs/evidence/v1-db/20261109T153003Z-a2-app-closed.json) · Redis 확정 0석 / MySQL 574석 / PG 승인·예매 없음 21건
- 2026-11-09T16:05:17.129456+00:00 · scenario-A · 종료 2026-11-09T16:15:49.722771+00:00 · 종료 코드 99
- 2026-11-09T16:18:12.544821+00:00 · app-closed · [스냅샷](../../docs/evidence/v1-db/20261109T153003Z-a3-app-closed.json) · Redis 확정 0석 / MySQL 592석 / PG 승인·예매 없음 28건
- 2026-11-09T16:21:34.174961+00:00 · scenario-B · 종료 2026-11-09T16:27:43.990276+00:00 · 종료 코드 99
- 2026-11-09T16:26:53.548442+00:00 · hold-contention · [스냅샷](../../docs/evidence/v1-db/20261109T153003Z-b1-hold-contention.json)

### v2-db-fixed

- 실행 시작(UTC): 2026-11-09T16:33:41.222646+00:00
- 실행 명령: `./load-test/run-stage.sh v2`
- 성격: 정식 측정
- 코드: `v2-db-fixed`

| 설정 | 값 |
| --- | --- |
| REDIS_CONFIG | `./infra/tuning/redis-v1.conf` |
| REDIS_APPENDONLY | `no` |
| REDIS_APPENDFSYNC | `everysec` |
| HOLD_DURATION | `30s` |
| PAYMENT_MODE | `mock` |
| ACTIVE_CHECK_MODE | `http` |
| SPRING_THREADS_VIRTUAL_ENABLED | `true` |

| 실행 | 선점 성공 | 평균 성공 TPS | 선점 p95/p99(ms) | 확정 | deadlock 증가 | 웹훅 p95(s) | Stream lag 최대 |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: |
| [20261109T163341Z-a1](v2-db-fixed/20261109T163341Z-a1/README.md) | 407.00 | 0.65 | 584.90 / 662.28 | 322.00 | 18.0 | — | — |
| [20261109T163341Z-a2](v2-db-fixed/20261109T163341Z-a2/README.md) | 463.00 | 0.75 | 1231.46 / 1903.16 | 363.00 | 18.0 | — | — |
| [20261109T163341Z-a3](v2-db-fixed/20261109T163341Z-a3/README.md) | 429.00 | 0.69 | 787.48 / 923.27 | 345.00 | 25.0 | — | — |
| [20261109T163341Z-b1](v2-db-fixed/20261109T163341Z-b1/README.md) | 2923.00 | 8.04 | 3471.49 / 3795.92 | — | 46.0 | — | — |

실행별 원본:

- 20261109T163341Z-a1: [대시보드](v2-db-fixed/20261109T163341Z-a1/dashboard.png) · [패널 PNG](v2-db-fixed/20261109T163341Z-a1/panels/) · [Prometheus JSON](v2-db-fixed/20261109T163341Z-a1/prometheus/) · [k6 원본](v2-db-fixed/20261109T163341Z-a1/summary.json)
- 20261109T163341Z-a2: [대시보드](v2-db-fixed/20261109T163341Z-a2/dashboard.png) · [패널 PNG](v2-db-fixed/20261109T163341Z-a2/panels/) · [Prometheus JSON](v2-db-fixed/20261109T163341Z-a2/prometheus/) · [k6 원본](v2-db-fixed/20261109T163341Z-a2/summary.json)
- 20261109T163341Z-a3: [대시보드](v2-db-fixed/20261109T163341Z-a3/dashboard.png) · [패널 PNG](v2-db-fixed/20261109T163341Z-a3/panels/) · [Prometheus JSON](v2-db-fixed/20261109T163341Z-a3/prometheus/) · [k6 원본](v2-db-fixed/20261109T163341Z-a3/summary.json)
- 20261109T163341Z-b1: [대시보드](v2-db-fixed/20261109T163341Z-b1/dashboard.png) · [패널 PNG](v2-db-fixed/20261109T163341Z-b1/panels/) · [Prometheus JSON](v2-db-fixed/20261109T163341Z-b1/prometheus/) · [k6 원본](v2-db-fixed/20261109T163341Z-b1/summary.json)

장애 주입과 상태 스냅샷(UTC, 시간순):

- 2026-11-09T16:36:53.421296+00:00 · scenario-A · 종료 2026-11-09T16:47:25.123627+00:00 · 종료 코드 0
- 2026-11-09T16:49:48.023666+00:00 · v2-completion · [스냅샷](../../docs/evidence/v2-db-fixed/20261109T163341Z-a1-v2-completion.json) · Redis 확정 0석 / MySQL 668석 / PG 승인·예매 없음 0건
- 2026-11-09T16:52:59.907934+00:00 · scenario-A · 종료 2026-11-09T17:03:30.614182+00:00 · 종료 코드 0
- 2026-11-09T17:05:54.857876+00:00 · v2-completion · [스냅샷](../../docs/evidence/v2-db-fixed/20261109T163341Z-a2-v2-completion.json) · Redis 확정 0석 / MySQL 767석 / PG 승인·예매 없음 0건
- 2026-11-09T17:09:05.636957+00:00 · scenario-A · 종료 2026-11-09T17:19:37.160251+00:00 · 종료 코드 0
- 2026-11-09T17:22:00.382060+00:00 · v2-completion · [스냅샷](../../docs/evidence/v2-db-fixed/20261109T163341Z-a3-v2-completion.json) · Redis 확정 0석 / MySQL 746석 / PG 승인·예매 없음 0건
- 2026-11-09T17:25:24.620849+00:00 · scenario-B · 종료 2026-11-09T17:31:38.563241+00:00 · 종료 코드 99
- 2026-11-09T17:30:41.078951+00:00 · throughput-ceiling · [스냅샷](../../docs/evidence/v2-db-fixed/20261109T163341Z-b1-throughput-ceiling.json)
- 2026-11-09T17:34:11.916381+00:00 · v2-after-locks · [스냅샷](../../docs/evidence/v2-db-fixed/20261109T163341Z-b1-v2-after-locks.json)
- 2026-11-09T17:38:02.948984+00:00 · vt-off-scenario-B · 종료 2026-11-09T17:44:23.757122+00:00 · 종료 코드 99

### v3-pubsub

- 실행 시작(UTC): 2026-11-09T17:48:49.650218+00:00
- 실행 명령: `./load-test/run-stage.sh v3 --skip-a`
- 성격: 정식 측정
- 코드: `v3-pubsub`

| 설정 | 값 |
| --- | --- |
| REDIS_CONFIG | `./infra/tuning/redis-v1.conf` |
| REDIS_APPENDONLY | `no` |
| REDIS_APPENDFSYNC | `everysec` |
| HOLD_DURATION | `30s` |
| PAYMENT_MODE | `mock` |
| ACTIVE_CHECK_MODE | `http` |
| SPRING_THREADS_VIRTUAL_ENABLED | `true` |

| 실행 | 선점 성공 | 평균 성공 TPS | 선점 p95/p99(ms) | 확정 | deadlock 증가 | 웹훅 p95(s) | Stream lag 최대 |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: |
| [20261109T174849Z-b1](v3-pubsub/20261109T174849Z-b1/README.md) | 6926.00 | 19.24 | 4.53 / 8.14 | — | 0.0 | — | — |
| [20261109T174849Z-worker-kill](v3-pubsub/20261109T174849Z-worker-kill/README.md) | 501.00 | 0.81 | 419.22 / 553.64 | 374.00 | 0.0 | — | — |

실행별 원본:

- 20261109T174849Z-b1: [대시보드](v3-pubsub/20261109T174849Z-b1/dashboard.png) · [패널 PNG](v3-pubsub/20261109T174849Z-b1/panels/) · [Prometheus JSON](v3-pubsub/20261109T174849Z-b1/prometheus/) · [k6 원본](v3-pubsub/20261109T174849Z-b1/summary.json)
- 20261109T174849Z-worker-kill: [대시보드](v3-pubsub/20261109T174849Z-worker-kill/dashboard.png) · [패널 PNG](v3-pubsub/20261109T174849Z-worker-kill/panels/) · [Prometheus JSON](v3-pubsub/20261109T174849Z-worker-kill/prometheus/) · [k6 원본](v3-pubsub/20261109T174849Z-worker-kill/summary.json)

장애 주입과 상태 스냅샷(UTC, 시간순):

- 2026-11-09T17:52:30.695597+00:00 · scenario-B · 종료 2026-11-09T17:58:37.231814+00:00 · 종료 코드 0
- 2026-11-09T18:04:25.787381+00:00 · fault-worker-kill · 종료 2026-11-09T18:14:57.014822+00:00 · 종료 코드 0
- 2026-11-09T18:04:37.790270+00:00 · worker-before-kill · [스냅샷](../../docs/evidence/v3-pubsub/20261109T174849Z-worker-kill-worker-before-kill.json) · Redis 확정 0석 / MySQL 0석 / PG 승인·예매 없음 0건
- 2026-11-09T18:04:38.201324+00:00 · reservation-worker SIGKILL
- 2026-11-09T18:04:45.138313+00:00 · worker-after-kill · [스냅샷](../../docs/evidence/v3-pubsub/20261109T174849Z-worker-kill-worker-after-kill.json) · Redis 확정 0석 / MySQL 0석 / PG 승인·예매 없음 0건
- 2026-11-09T18:04:45.562355+00:00 · reservation-worker restarted
- 2026-11-09T18:05:04.221796+00:00 · worker-after-restart · [스냅샷](../../docs/evidence/v3-pubsub/20261109T174849Z-worker-kill-worker-after-restart.json) · Redis 확정 599석 / MySQL 581석 / PG 승인·예매 없음 9건

### v4-streams

- 실행 시작(UTC): 2026-11-09T18:18:40.833980+00:00
- 실행 명령: `./load-test/run-stage.sh v4`
- 성격: 정식 측정
- 코드: `v4-streams`

| 설정 | 값 |
| --- | --- |
| REDIS_CONFIG | `./infra/tuning/redis-v1.conf` |
| REDIS_APPENDONLY | `no` |
| REDIS_APPENDFSYNC | `everysec` |
| HOLD_DURATION | `30s` |
| PAYMENT_MODE | `mock` |
| ACTIVE_CHECK_MODE | `http` |
| SPRING_THREADS_VIRTUAL_ENABLED | `true` |

| 실행 | 선점 성공 | 평균 성공 TPS | 선점 p95/p99(ms) | 확정 | deadlock 증가 | 웹훅 p95(s) | Stream lag 최대 |
| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: |
| [20261109T181840Z-a1](v4-streams/20261109T181840Z-a1/README.md) | 274.00 | 0.44 | 32.94 / 49.60 | 208.00 | 0.0 | 0.01 | 2.00 |
| [20261109T181840Z-a2](v4-streams/20261109T181840Z-a2/README.md) | 173.00 | 0.28 | 20.82 / 3654.18 | 128.00 | 0.0 | 0.01 | 1.00 |
| [20261109T181840Z-a3](v4-streams/20261109T181840Z-a3/README.md) | 459.00 | 0.74 | 195.51 / 255.88 | 372.00 | 0.0 | 0.16 | 12.00 |
| [20261109T181840Z-b1](v4-streams/20261109T181840Z-b1/README.md) | 7014.00 | 19.48 | 5.02 / 8.52 | — | 0.0 | — | 0.00 |
| [20261109T181840Z-mysql-stop](v4-streams/20261109T181840Z-mysql-stop/README.md) | 384.00 | 0.62 | 46.42 / 880.42 | 287.00 | 0.0 | 0.02 | 238.00 |
| [20261109T181840Z-worker-kill](v4-streams/20261109T181840Z-worker-kill/README.md) | 344.00 | 0.55 | 205.05 / 370.79 | 267.00 | 0.0 | 0.09 | 131.00 |
| [20261109T181840Z-worker-kill-retry-200542](v4-streams/20261109T181840Z-worker-kill-retry-200542/README.md) | 466.00 | 0.75 | 56.87 / 92.65 | 341.00 | 0.0 | 0.03 | 6.00 |

실행별 원본:

- 20261109T181840Z-a1: [대시보드](v4-streams/20261109T181840Z-a1/dashboard.png) · [패널 PNG](v4-streams/20261109T181840Z-a1/panels/) · [Prometheus JSON](v4-streams/20261109T181840Z-a1/prometheus/) · [k6 원본](v4-streams/20261109T181840Z-a1/summary.json)
- 20261109T181840Z-a2: [대시보드](v4-streams/20261109T181840Z-a2/dashboard.png) · [패널 PNG](v4-streams/20261109T181840Z-a2/panels/) · [Prometheus JSON](v4-streams/20261109T181840Z-a2/prometheus/) · [k6 원본](v4-streams/20261109T181840Z-a2/summary.json)
- 20261109T181840Z-a3: [대시보드](v4-streams/20261109T181840Z-a3/dashboard.png) · [패널 PNG](v4-streams/20261109T181840Z-a3/panels/) · [Prometheus JSON](v4-streams/20261109T181840Z-a3/prometheus/) · [k6 원본](v4-streams/20261109T181840Z-a3/summary.json)
- 20261109T181840Z-b1: [대시보드](v4-streams/20261109T181840Z-b1/dashboard.png) · [패널 PNG](v4-streams/20261109T181840Z-b1/panels/) · [Prometheus JSON](v4-streams/20261109T181840Z-b1/prometheus/) · [k6 원본](v4-streams/20261109T181840Z-b1/summary.json)
- 20261109T181840Z-mysql-stop: [대시보드](v4-streams/20261109T181840Z-mysql-stop/dashboard.png) · [패널 PNG](v4-streams/20261109T181840Z-mysql-stop/panels/) · [Prometheus JSON](v4-streams/20261109T181840Z-mysql-stop/prometheus/) · [k6 원본](v4-streams/20261109T181840Z-mysql-stop/summary.json)
- 20261109T181840Z-worker-kill: [대시보드](v4-streams/20261109T181840Z-worker-kill/dashboard.png) · [패널 PNG](v4-streams/20261109T181840Z-worker-kill/panels/) · [Prometheus JSON](v4-streams/20261109T181840Z-worker-kill/prometheus/) · [k6 원본](v4-streams/20261109T181840Z-worker-kill/summary.json)
- 20261109T181840Z-worker-kill-retry-200542: [대시보드](v4-streams/20261109T181840Z-worker-kill-retry-200542/dashboard.png) · [패널 PNG](v4-streams/20261109T181840Z-worker-kill-retry-200542/panels/) · [Prometheus JSON](v4-streams/20261109T181840Z-worker-kill-retry-200542/prometheus/) · [k6 원본](v4-streams/20261109T181840Z-worker-kill-retry-200542/summary.json)

장애 주입과 상태 스냅샷(UTC, 시간순):

- 2026-11-09T18:22:03.457701+00:00 · scenario-A · 종료 2026-11-09T18:32:35.175817+00:00 · 종료 코드 0
- 2026-11-09T18:38:13.274351+00:00 · scenario-A · 종료 2026-11-09T18:48:45.653720+00:00 · 종료 코드 0
- 2026-11-09T18:54:25.270699+00:00 · scenario-A · 종료 2026-11-09T19:04:56.937648+00:00 · 종료 코드 0
- 2026-11-09T19:10:48.786447+00:00 · scenario-B · 종료 2026-11-09T19:16:59.055387+00:00 · 종료 코드 0
- 2026-11-09T19:22:42.675139+00:00 · fault-worker-kill · 종료 2026-11-09T19:33:14.056755+00:00 · 종료 코드 0
- 2026-11-09T19:22:54.697255+00:00 · stream-before-kill · [스냅샷](../../docs/evidence/v4-streams/20261109T181840Z-worker-kill-stream-before-kill.json) · Redis 확정 0석 / MySQL 0석 / PG 승인·예매 없음 0건
- 2026-11-09T19:22:55.122727+00:00 · reservation-worker SIGKILL
- 2026-11-09T19:23:02.086239+00:00 · stream-after-kill · [스냅샷](../../docs/evidence/v4-streams/20261109T181840Z-worker-kill-stream-after-kill.json) · Redis 확정 0석 / MySQL 0석 / PG 승인·예매 없음 0건
- 2026-11-09T19:23:02.546730+00:00 · reservation-worker restarted
- 2026-11-09T19:23:21.183277+00:00 · stream-after-recovery · [스냅샷](../../docs/evidence/v4-streams/20261109T181840Z-worker-kill-stream-after-recovery.json) · Redis 확정 356석 / MySQL 356석 / PG 승인·예매 없음 0건
- 2026-11-09T19:38:54.061795+00:00 · fault-mysql-stop · 종료 2026-11-09T19:49:26.559978+00:00 · 종료 코드 0
- 2026-11-09T19:39:06.239920+00:00 · mysql-before-outage · [스냅샷](../../docs/evidence/v4-streams/20261109T181840Z-mysql-stop-mysql-before-outage.json) · Redis 확정 0석 / MySQL 0석 / PG 승인·예매 없음 0건
- 2026-11-09T19:39:10.660223+00:00 · MySQL stopped
- 2026-11-09T19:39:15.820868+00:00 · mysql-down · [스냅샷](../../docs/evidence/v4-streams/20261109T181840Z-mysql-stop-mysql-down.json) · Redis 확정 6석 / MySQL 조회 불가석 / PG 승인·예매 없음 조회 불가건
- 2026-11-09T19:39:46.392409+00:00 · MySQL started
- 2026-11-09T19:39:59.290720+00:00 · mysql-recovered · [스냅샷](../../docs/evidence/v4-streams/20261109T181840Z-mysql-stop-mysql-recovered.json) · Redis 확정 481석 / MySQL 481석 / PG 승인·예매 없음 0건
- 2026-11-09T19:55:52.197089+00:00 · vt-off-scenario-B · 종료 2026-11-09T20:02:02.459461+00:00 · 종료 코드 0
- 2026-11-09T20:09:04.216137+00:00 · fault-worker-kill · 종료 2026-11-09T20:19:36.655767+00:00 · 종료 코드 0
- 2026-11-09T20:09:37.320848+00:00 · stream-before-kill · [스냅샷](../../docs/evidence/v4-streams/20261109T181840Z-worker-kill-retry-200542-stream-before-kill.json) · Redis 확정 253석 / MySQL 350석 / PG 승인·예매 없음 0건
- 2026-11-09T20:09:37.730453+00:00 · reservation-worker SIGKILL
- 2026-11-09T20:09:44.823794+00:00 · stream-after-kill · [스냅샷](../../docs/evidence/v4-streams/20261109T181840Z-worker-kill-retry-200542-stream-after-kill.json) · Redis 확정 465석 / MySQL 465석 / PG 승인·예매 없음 0건
- 2026-11-09T20:10:22.223379+00:00 · stream-during-outage · [스냅샷](../../docs/evidence/v4-streams/20261109T181840Z-worker-kill-retry-200542-stream-during-outage.json) · Redis 확정 465석 / MySQL 465석 / PG 승인·예매 없음 0건
- 2026-11-09T20:10:22.641041+00:00 · reservation-worker restarted
- 2026-11-09T20:10:40.643790+00:00 · stream-after-recovery · [스냅샷](../../docs/evidence/v4-streams/20261109T181840Z-worker-kill-retry-200542-stream-after-recovery.json) · Redis 확정 465석 / MySQL 465석 / PG 승인·예매 없음 0건

## MySQL 중단 뒤 확정 상태

- [MySQL 중단 실행](v4-streams/20261109T181840Z-mysql-stop/README.md): 2026-11-09T19:39:10.660223+00:00 ~ 2026-11-09T19:39:46.392409+00:00. 복구 뒤 결제 승인 대비 확정 239/239 (100.00%), 중단 창의 웹훅 p95 최대 0.02초, Stream lag 최대 238.00건. 분자·분모는 [복구 스냅샷](../../docs/evidence/v4-streams/20261109T181840Z-mysql-stop-mysql-recovered.json)에서 확인한다.

## waiting-api 중단 중 active 선점

- waiting-api 중단 실행 원본이 아직 없다.

## 가상 스레드 켬 vs 끔

값의 순서는 켬 / 끔이다. CPU는 reservation-api 프로세스 사용률이다. 가상 스레드에서 Tomcat busy 값이 -1이면 측정 불가로 표시한다.

| 단계 | 가설 | 켬 선점 TPS | 끔 선점 TPS | p95·p99(ms) | Tomcat/JVM 스레드 | Hikari active/pending | pinning 건수 | CPU |
| --- | --- | ---: | ---: | --- | --- | --- | --- | --- |
| [v2-db-fixed 켬](v2-db-fixed/20261109T163341Z-b1/README.md) / [끔](v2-db-fixed/vt-off/20261109T163341Z-b1/README.md) | 상영 행 락 병목에서 켬/끔 차이 확인 | 2.67 | 2.57 | 3471.49 / 10000.48 · 3795.92 / 10000.85 | 측정 불가 / 200.00 · 62.00 / 240.00 | 100.00 / 100.00 · 6301.00 / 101.00 | 0 / 0 | 68.00% / 49.00% |
| [v4-streams 켬](v4-streams/20261109T181840Z-b1/README.md) / [끔](v4-streams/vt-off/20261109T181840Z-b1/README.md) | HTTP 동기 호출 구간에서 켬/끔 차이 확인 | 4.35 | 4.36 | 5.02 / 7.04 · 8.52 / 29.16 | 측정 불가 / 37.00 · 87.00 / 276.00 | 1.00 / 1.00 · 0.00 / 0.00 | 0 / 0 | 76.00% / 84.00% |

- v2-db-fixed on: [JFR](../../docs/evidence/v2-db-fixed/20261109T163341Z-vt-on.jfr) · [pinning 원본](../../docs/evidence/v2-db-fixed/20261109T163341Z-vt-on-pinning.txt)
- v2-db-fixed off: [JFR](../../docs/evidence/v2-db-fixed/20261109T163341Z-vt-off.jfr) · [pinning 원본](../../docs/evidence/v2-db-fixed/20261109T163341Z-vt-off-pinning.txt)
- v4-streams on: [JFR](../../docs/evidence/v4-streams/20261109T181840Z-vt-on.jfr) · [pinning 원본](../../docs/evidence/v4-streams/20261109T181840Z-vt-on-pinning.txt)
- v4-streams off: [JFR](../../docs/evidence/v4-streams/20261109T181840Z-vt-off.jfr) · [pinning 원본](../../docs/evidence/v4-streams/20261109T181840Z-vt-off-pinning.txt)

## DB 단독 기준과 V2 선점 상한

- [DB 단독 원본](db-baseline/20261109T134320Z/README.md): fsync p50 2.867ms, p99 3.097ms; hot row 최대 259.00 TPS. 정식 측정값이다.
- V2 B 선점: 8.04 TPS. 두 값의 차이는 앱 처리·네트워크·대기열 조건을 포함한다.

## V6 AOF 설정 비교

| 설정 | 선점 성공 TPS | p95(ms) | p99(ms) | 원본 |
| --- | ---: | ---: | ---: | --- |
| everysec | — | — | — | — |
| always | — | — | — | — |
