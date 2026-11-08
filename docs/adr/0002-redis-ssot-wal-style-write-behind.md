# 좌석 상태의 SSOT를 Redis에 두고, 확정 기록은 WAL처럼 나중에 MySQL에 적재한다

좌석 선점과 확정은 예매 오픈 순간에 몰리고, MySQL 행 락으로 처리하면 상영 행과 인기 좌석 행에서 직렬화와 deadlock이 생긴다. 그래서 좌석의 현재 상태(빈 좌석 · 선점 · 확정)는 Redis만 갖고, 확정은 Lua 한 번으로 Redis에 반영하면서 같은 스크립트 안에서 stream에 기록한다. MySQL에는 worker가 stream을 읽어 확정 기록을 적재하고, 커밋한 항목만 ACK한다. DB의 WAL처럼 로그를 먼저 쓰고 반영은 나중에 하는 구조다.

## Consequences

- WAL과 달리 Redis AOF는 `appendfsync everysec`으로 둔다. 쓰기마다 fsync하는 `always`보다 처리량이 높은 대신, 장애 시 최대 1초 분량의 기록을 잃을 수 있다. 잃은 확정은 MySQL 기록과 PG 결제 내역을 대조하는 대사 작업으로 찾아 복구하거나 결제 취소한다.
- 선점은 MySQL에 남기지 않는다. 선점 이력이 필요해지면 stream 이벤트로 따로 적재한다.
- `reservation_seats`의 UNIQUE(screening_id, seat_no)는 Redis 판정이 틀렸을 때 이중 예매를 막는 마지막 방어선으로 유지한다.
