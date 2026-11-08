# V2 문제 → 해결

V1에서 요청 순서가 다른 좌석 잠금과 상영 FK S-lock 뒤 X-lock 전환이 deadlock을 만들었다. V2는 상영 행을 먼저 X-lock으로 직렬화한 다음 좌석 번호 순서대로 잠근다. 같은 두 경로를 로컬에서 다시 실행했을 때 `v2-baseline.json`과 `v2-after-locks.json`의 `deadlockCount` 증가분은 0이었다.

상영 행 직렬화는 새 처리량 상한을 만든다. 이론적인 상한은 **1 ÷ 한 선점 트랜잭션의 상영 행 X-lock 점유 시간(초)**로 계산한다. 정식 측정일에는 같은 환경에서 구한 평균 점유 시간으로 계산값을 만들고, k6 시나리오 B에서 p95 1초 또는 예상하지 않은 오류율 1%를 넘기기 직전 구간의 실측 처리량과 비교한다. 로컬 스모크의 시간 값은 이 계산에 쓰지 않는다.

`throughput-ceiling.json`은 상영 행을 시험용 트랜잭션으로 잠가 둔 동안의 `performance_schema.data_locks`·`data_lock_waits`, InnoDB row lock 계측과 Hikari pending을 보존한다. 이 주입 상황에서 생긴 연결 획득 실패는 처리량 결과가 아니다. `late-payment-before-cancel.json`과 `late-payment-after-cancel.json`은 만료 뒤 좌석이 다시 선점된 결제의 READY→CANCELLED 전환을 보존한다. 좌석이 그대로 비어 있으면 웹훅으로 확정된다.

InnoDB의 `LATEST DETECTED DEADLOCK` 절은 마지막 V1 deadlock 원문을 계속 보여 준다. V2의 신규 deadlock 여부는 전후 누적값으로 판단한다.
