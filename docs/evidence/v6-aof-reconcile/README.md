# V6 문제 → 해결

V4의 Stream은 worker 재시작을 견디지만 Redis 자체가 재시작되면 마지막 AOF fsync 이후의 확정은 사라질 수 있다. V6는 `appendonly yes`, `appendfsync everysec`과 이름 붙인 Redis 볼륨을 사용한다. 예매 API는 시작하거나 Redis `run_id`가 바뀌면 readiness를 닫고 MySQL 확정 좌석을 Redis에 복원한 뒤 PG 승인 내역을 대사한다.

`pg-recovered-free.json`은 PG 승인 후 Redis 결제 컨텍스트 키를 골라 지운 결제를 암호화된 PG `customData`로 확정한 목록을 담는다. `pg-cancelled-taken.json`은 같은 손실 상황에서 좌석이 재선점돼 결제를 취소한 목록을 담는다. `pg-stream-fallback.json`은 이전 단계처럼 `customData`가 없는 결제를 남아 있는 Stream 이벤트로 복원한 근거다.

`redis-before-kill.json` → `redis-after-restart.json` → `redis-after-reconcile.json`은 Redis SIGKILL/AOF 재시작 전후의 확정 좌석, MySQL 적재 좌석, AOF 설정, 결제 승인·확정·취소 목록을 보존한다. 로컬 스모크에서 확정 좌석의 이중 판매와 미해소 PG 승인은 없었다. 이 결과는 정식 부하 측정값이 아니다. 정식 유실 건수와 성능은 공통 AWS 측정일에 기록한다.
