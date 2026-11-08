# v2-db: throughput-ceiling

- 시각: 2026-11-08T08:09:24.923729+00:00
- 실행한 쿼리·명령: 같은 이름의 JSON 파일 `queries`에 원문을 보존했다.
- InnoDB의 최근 deadlock 절은 이전 단계 기록도 유지된다. 이번 단계의 신규 건수는 전후 누적값 차이로 판정한다.

| 항목 | 결과 |
| --- | ---: |
| 좌석·상영 락 | 70건 |
| 락 대기 관계 | 595건 |
| 최근 deadlock 기록 | 있음 |
| deadlock 누적값 | 4건 |
| InnoDB row lock 계측 | 5개 |
| Hikari pending 시계열 | 1개 |
| Hikari pending 즉시값 | 87.0 |
| DB PAID·예매 없음 | 0건 |
| PG PAID·로컬 예매 없음 | 0건 |

해석: 상영 행 직렬화 중의 DB 락 대기와 Hikari 대기를 확인한다.
