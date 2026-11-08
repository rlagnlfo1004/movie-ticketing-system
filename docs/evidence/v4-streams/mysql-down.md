# v4-streams: mysql-down

- 시각: 2026-11-08T08:42:04.706581+00:00
- 실행한 쿼리·명령: 같은 이름의 JSON 파일 `queries`에 원문을 보존했다.
- InnoDB의 최근 deadlock 절은 이전 단계 기록도 유지된다. 이번 단계의 신규 건수는 전후 누적값 차이로 판정한다.

| 항목 | 결과 |
| --- | ---: |
| 좌석·상영 락 | 조회 불가 |
| 락 대기 관계 | 조회 불가 |
| 최근 deadlock 기록 | 조회 불가 |
| deadlock 누적값 | 조회 불가 |
| InnoDB row lock 계측 | 조회 불가 |
| Hikari pending 시계열 | 1개 |
| Hikari pending 즉시값 | 0.0 |
| DB PAID·예매 없음 | 조회 불가 |
| PG PAID·로컬 예매 없음 | 조회 불가 |
| Redis 확정 좌석 | 84석 |
| MySQL 적재 좌석 | 조회 불가 |
| Redis에만 있는 좌석 | 조회 불가 |
| MySQL 조회 가능 | 아니오 |
| Stream 길이 | 6건 |
| Stream pending | 1건 |
| Stream lag | 2건 |
| DLQ 최근 항목 | 0건 |

해석: MySQL 중단 중 Redis Stream 길이와 lag를 기록한다.
