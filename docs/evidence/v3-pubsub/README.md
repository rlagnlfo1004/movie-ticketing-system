# V3 문제 → 해결

V2의 상영 행 X-lock은 한 상영의 선점 요청을 직렬화한다. V3는 좌석 선점과 예매 확정의 판단을 Redis Lua로 옮겼다. Redis의 `seat:{sid}:reserved`가 현재 확정 좌석의 기준이고, worker는 Pub/Sub 이벤트를 받아 MySQL에 나중에 적재한다.

이 단계는 Pub/Sub의 유실을 재현한다. `worker-before-kill.json`에서 Redis에만 있던 좌석 3석은 앞선 개발 스모크 중 worker가 없거나 재시작 중이던 때의 누락이다. worker를 SIGKILL하고 새 결제를 확정하자 `worker-after-kill.json`의 차이가 4석으로 늘었다. worker를 다시 띄운 뒤 `worker-after-restart.json`에도 같은 네 좌석이 남았다. 새로 발생한 누락 한 석은 전후 JSON의 `missingMysqlSeats` 목록 차이로 식별할 수 있다. 이 로컬 주입 결과는 정식 부하 수치가 아니다.

V4에서는 Pub/Sub을 Redis Streams와 ACK로 바꿔 worker 재기동 뒤에도 미적재 확정을 읽도록 한다.
