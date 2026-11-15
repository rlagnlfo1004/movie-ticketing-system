# 결제 확정 측정 인수인계 (이력서 결제 과제 근거)

작성: 2026-11-15. 이 문서 하나만 보고 이어서 측정할 수 있게 쓴다.

## 목표

이력서 미르 블록의 결제 과제를 **최종 구조(V4 이후 Redis Lua 확정)** 기준 근거로 뒷받침한다. 현재 이력서 문장은 다음과 같다.

```
결제 직후 브라우저 종료로 누락되던 예매 확정을 PG 웹훅 멱등 처리로 바꿔 누락 0건
- 결제 완료를 클라이언트 완료 API로만 인지해, 결제 직후 브라우저를 닫으면 승인된 결제가 예매로 이어지지 않는 문제 상황
- 클라이언트와 별개로 예매를 확정하는 PG 웹훅 경로를 추가하고, 서명 검증 뒤 PG 결제 조회로 상태와 금액을 재확인해 확정
- 완료 API와 웹훅이 동시에 도착해도 결제 확정을 Lua script 한 번으로 처리해 예매는 한 번만 생성, 늦은 요청은 저장된 결과 반환
- 결제 승인 뒤 10%가 완료 API를 호출하지 않는 3만 명 예매 시나리오에서 승인됐지만 예매가 없는 결제 21~28건 → 0건
```

3번째 줄은 원래 V2 구조(MySQL 결제 행 `SELECT ... FOR UPDATE`와 `reservations.payment_id` UNIQUE)였다. 이력서 과제 순서가 대기열 → Lua 선점 → Streams → 결제가 되면서, 최종 구조인 `confirm-v4.lua`로 바꿨다.

## 구조 (코드 위치)

- 완료 API와 웹훅은 모두 `PaymentCompletionService.complete(paymentId)`로 들어간다. PG 결제 조회로 상태와 금액을 확인한 뒤 확정을 호출한다.
- 확정: `reservation-api/src/main/resources/lua/confirm-v4.lua`
  - `payment:{sid}:{paymentId}` 결과 키가 있으면 저장된 결과를 그대로 반환한다(늦게 온 요청).
  - 없으면 금액과 좌석 소유를 확인하고, 확정 좌석 SADD, 결과 키 SET, Stream XADD를 한 스크립트에서 처리한다.
  - 좌석이 만료 뒤 다른 사람에게 넘어갔으면 `EXPIRED_TAKEN`을 돌려주고, 서비스가 PG 결제를 취소한다.
- 웹훅 서명 검증: `StandardWebhookVerifier`, 수신: `PortOneWebhookController`
- 완료 API 생략: `load-test/load-test.js` 110행 `Math.random() < .1`이면 승인 뒤 완료 API를 호출하지 않는다.

## 현황 (기존 AWS 정식 A 측정에서 확인, 2026-11-15)

승인됐지만 예매가 없는 결제는 각 실행의 `docs/evidence/{stage}/{run}-{a}-after.json`의 `pgPaidWithoutReservation`이다. 「완료 API 확정」은 k6 `reservations_confirmed`(완료 API가 200을 받은 수)다.

| 단계 | 실행 | 승인 뒤 예매 없음 | PG 승인 | 확정 | 완료 API 확정 | 승인 − 완료 API |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| V1 (웹훅 없음) | 20261109T153003Z a1/a2/a3 | 26 / 21 / 28 (app-closed 스냅샷) | — | — | — | — |
| V2 (MySQL 결제 행 잠금) | 20261109T163341Z a1/a2/a3 | 0 / 0 / 0 | 346 / 396 / 382 | 346 / 396 / 382 | 322 / 363 / 345 | 24 / 33 / 37 |
| V4 (Lua 확정 + Streams) | 20261109T181840Z a1/a2/a3 | 0 / 0 / 0 | 233 / 143 / 406 | 233 / 143 / 406 | 208 / 128 / 372 | 25 / 15 / 34 |
| V6 (V4 + AOF, 대사) | 20261109T212410Z a1/a2/a3 | 0 / 0 / 0 | 364 / 406 / 413 | 364 / 406 / 413 | 338 / 365 / 374 | 26 / 41 / 39 |

결론: 최종 구조(V4, V6)에서도 「승인됐지만 예매 없음」은 0건이다. 이력서 4번째 줄의 「21~28건 → 0건」은 V1 대비 V2, V4, V6 모두에 성립한다.

## 동시 도착 스모크 완료 (2026-11-16)

로컬 Docker Compose에서 V4 이후 `confirm-v4.lua`가 담긴 JAR로 결제 100건을 측정했다. 결제마다 완료 API 20개와 서명된 웹훅 1개를 병렬 시작하고, 나중에 완료 API를 한 번 더 호출했다. **100건 모두 동시 API 응답과 늦은 요청의 `reservationId`가 일치했고, Redis 결과 키·Stream 이벤트·MySQL 예매가 결제당 각각 1건이었다.** 원본은 `docs/evidence/payment-confirm/20261115T150026Z-race.json`, 결과 설명은 같은 이름의 `.md`, 재실행 스크립트는 `load-test/payment-confirm-race-smoke.py`다.

이력서 3번째 줄의 Lua 멱등 처리 문장은 유지한다. 요청은 병렬로 시작했지만 Redis Lua 도착 시각을 계측한 것은 아니다.

## 남은 측정 범위

1. **웹훅으로만 확정된 건수가 정확하지 않음.** 위 표의 「승인 − 완료 API」에는 완료 API를 생략한 10%와, 완료 API를 호출했지만 200을 받지 못한 경우가 섞여 있다. 경로별 확정 건수는 기록하지 않았다.
2. **실제 PortOne 미측정.** 모의 PG의 승인 0.5~2초, 웹훅 0.2~0.8초, 첫 발송 실패 1%는 가정값이다. 실제 PortOne은 V2 API 인증 조회와 사전 등록 200까지만 확인했다(#22, `docs/agents/portone-live-runbook.md`).

## 재실행 · 추가 측정 절차

### 1. 동시 도착 스모크 재실행 (로컬, V4 이후 코드)

1. `python3 infra/stages/ensure-local-env.py` 다음 `docker compose up -d nginx`로 로컬 스택을 띄운다.
2. `python3 load-test/payment-confirm-race-smoke.py --payments 100 --callers 20`을 실행한다. 스크립트가 좌석 선점, 모의 PG 승인, 20개 완료 API와 서명 웹훅 병렬 요청, 늦은 재요청, Redis·Stream·MySQL 확인을 수행한다.
3. 생성된 `docs/evidence/payment-confirm/*-race.json`에서 `passed: 100`, `dbDuplicateRows: []`, 각 결제의 `passed: true`를 확인한다.

다른 스모크를 같은 DB에서 실행했을 수 있으므로 전체 예매 수가 100건인지 비교하지 않는다. 각 결제 ID의 예매 행·Redis 결과·Stream 이벤트를 검사한다.

### 2. 경로별 확정 건수 (선택)

`PaymentCompletionService`나 웹훅 컨트롤러에 확정 경로(완료 API, 웹훅)별 카운터를 두고, 최초 확정(결과 키가 없던 경우)만 센다. k6 A 시나리오를 한 번 돌려 「웹훅으로만 확정 = 완료 API 생략분」인지 맞춘다.

### 3. 실제 PortOne (#22)

`docs/agents/portone-live-runbook.md` 절차를 따른다. 결제창 승인, 웹훅 지연, 웹훅 재전송을 실제 값으로 기록한다.

## 완료 조건

- 1번 스모크 결과(결제 100건, 중복 예매 0건, 늦은 요청이 같은 reservationId 반환)를 `docs/evidence/`에 JSON과 md로 남긴다. **완료.**
- 결과에 따라 이력서 3번째 줄을 유지하거나 고친다. **현 문장 유지.** 이력서 원본은 `~/Desktop/Study/resume/이력서/이력서.md`이고, Figma 이력서 v5.0 섹션(1458:2)의 결제 과제 본문은 노드 1458:144다.
- 2번, 3번은 이력서 문장에 쓰지 않는 한 필수가 아니다. 면접 답변에서 「웹훅으로 몇 건 확정됐냐」를 물으면 위 표의 「승인 − 완료 API」에 다른 실패가 섞여 있다고 답한다.

## 관련

- 리포트: `docs/reports/seat-redesign-v0-v7.md`
- 측정 결과 인덱스: `load-test/results/README.md` (V1 app-closed 84~88행, V2 v2-completion 126~130행)
- 결제 컨텍스트 명세: `docs/api/payment-context.md`
