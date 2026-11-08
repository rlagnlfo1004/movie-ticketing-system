# 좌석 예매 측정 하네스

`load-test.js`는 두 시나리오를 쓴다. A는 3만 명을 10초 동안 등록해 입장, 좌석 조회, 1~4석 선점, 모의 결제, 클라이언트 완료 호출까지 진행한다. 70%가 가운데 30% 구역을 고르고, 선점 충돌 시 최대 3회 다시 고른다. 선점 성공자의 15%는 결제를 떠나고 결제자의 10%는 완료 호출을 생략한다. B는 입장 토큰을 미리 Redis에 넣고 초당 100, 200, 400, 800, 1,600, 3,200건을 각각 60초 동안 보낸다.

```bash
SCENARIO=A STAGE=v1 ./load-test/run.sh
SCENARIO=B STAGE=v1 ./load-test/run.sh
SMOKE=1 SCENARIO=A STAGE=v1 ./load-test/run.sh
```

결과는 `load-test/results/<stage>/<run>/summary.json`에 쓴다. 단계별 정식 측정은 시나리오 A를 세 번, B를 한 번 실행한다. `STAGE_SECONDS`로 B 구간 길이를 바꿀 수 있다. B의 각 구간 p95와 예상하지 않은 오류율은 k6 summary에 구분해 남긴다. `SEAT_TAKEN`은 경쟁 결과이므로 오류율에 넣지 않고 별도 건수로 남긴다. **좌석 1,000개가 소진된 뒤의 B 수치는 성공 선점 처리량이 아니라 충돌 응답 처리량이다.** 성공 선점 TPS와 함께 읽어야 한다.

V4에서는 `v4-pending-recovery-smoke.py`, `v4-mysql-outage-smoke.py`, `v4-idempotency-smoke.py`, `v4-dlq-smoke.py`로 장애 경로를 확인한다. 첫 두 스크립트는 로컬 worker를 SIGKILL하거나 MySQL 컨테이너를 멈췄다가 다시 띄운다. 수치 비교에는 사용하지 않는다.

모의 PG는 승인 지연 500~2,000ms, 웹훅 지연 200~800ms, 첫 발송 의도적 실패 1%를 사용한다. 모두 실측값이 아닌 가정이다. 웹훅 시크릿과 API 시크릿은 로컬 테스트 전용 값이다. 서버가 `POST /payments/{id}/pre-register`로 금액을 등록한 뒤에만 승인 `POST /test/payments/{id}/approve`가 성공한다. 승인 경로는 부하 하네스 전용이고, 조회·취소는 PortOne V2 경로와 응답의 주요 필드를 따른다.

V6에서는 선점 응답의 `customData`를 모의 결제 승인 요청에도 전달한다. 모의 PG의 `GET /payments`는 페이지 조회와 `customData` 반환을 지원한다. `v6-reconcile-smoke.py`는 결제 컨텍스트 Redis 키를 골라 지우고 PG 내역으로 확정·취소한 뒤 Redis SIGKILL/AOF 재시작을 확인한다. `v6-stream-fallback-smoke.py`는 이전 단계처럼 `customData`가 없는 결제를 Stream 이벤트로 복원한다.

좌석 API가 구현되기 전에는 로컬 계약 스텁으로 k6 호출 흐름만 확인한다. 이 실행의 수치는 성능 결과로 사용하지 않는다.

```bash
docker compose --profile harness-smoke up -d harness-stub mock-pg redis
docker compose --profile load-test run --rm --no-deps k6 run \
  -e SMOKE=1 -e BASE_URL=http://harness-stub:18080 \
  -e WEBHOOK_URL=http://harness-stub:18080/api/v1/webhooks/portone \
  /load-test/load-test.js
```
