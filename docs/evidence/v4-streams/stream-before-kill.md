# v4-streams: stream-before-kill

- 시각: 2026-11-08T08:40:01.012167+00:00
- 실행한 쿼리·명령: 같은 이름의 JSON 파일 `queries`에 원문을 보존했다.
- InnoDB의 최근 deadlock 절은 이전 단계 기록도 유지된다. 이번 단계의 신규 건수는 전후 누적값 차이로 판정한다.

| 항목 | 결과 |
| --- | ---: |
| 좌석·상영 락 | 0건 |
| 락 대기 관계 | 0건 |
| 최근 deadlock 기록 | 있음 |
| deadlock 누적값 | 4건 |
| InnoDB row lock 계측 | 5개 |
| Hikari pending 시계열 | 1개 |
| Hikari pending 즉시값 | 0.0 |
| DB PAID·예매 없음 | 0건 |
| PG PAID·로컬 예매 없음 | 5건 |
| Redis 확정 좌석 | 81석 |
| MySQL 적재 좌석 | 76석 |
| Redis에만 있는 좌석 | 5석 |
| MySQL 조회 가능 | 예 |
| Stream 길이 | 3건 |
| Stream pending | 1건 |
| Stream lag | 0건 |
| DLQ 최근 항목 | 0건 |

해석: worker 종료 직전 미확인 Stream 항목을 기록한다.
