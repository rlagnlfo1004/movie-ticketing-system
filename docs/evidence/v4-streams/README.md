# V4 문제 → 해결

V3의 Pub/Sub은 worker가 죽은 동안 발행된 확정을 다시 전달하지 못했다. V4는 확정 Lua 안에서 Redis Stream에 이벤트를 함께 쓰고, worker가 MySQL 커밋 뒤 XACK한다. 재기동한 worker는 XAUTOCLAIM으로 30초 이상 미확인 상태인 항목을 회수한다. 같은 이벤트가 다시 전달돼도 `INSERT IGNORE`와 기존 예매 ID 확인으로 좌석 수를 두 번 줄이지 않는다.

`stream-before-kill.json`과 `stream-after-kill.json`에는 SIGKILL로 남긴 PEL 한 건이 있고, `stream-after-recovery.json`에는 PEL 0건과 DB에 적재된 좌석이 있다. 전체 스모크를 다시 실행한 결과도 같은 경로로 통과했다. V3 개발 중 생긴 이전 누락 네 석은 그대로 남아 있으므로, V4에서 새로 만든 좌석의 유실은 **전후 `missingMysqlSeats` 목록의 차이**로 판단한다.

`mysql-before-outage.json` → `mysql-down.json` → `mysql-recovered.json`에는 MySQL 중단 중 Stream 길이·lag 증가와 재개 뒤 lag·pending 0건이 남아 있다. MySQL이 꺼진 시점의 DB 관련 값은 `null`이며 Markdown에는 「조회 불가」로 썼다. `stream-duplicate-acked.json`은 중복 전달 후에도 DB 좌석 수가 한 번만 줄어든 근거이고, `dlq-after-retries.json`은 초기 전달과 세 번의 재전달이 모두 실패한 시험 이벤트의 DLQ 내용을 담는다.

Webhook 응답 p95는 Grafana 대시보드의 별도 패널과 원본 Prometheus JSON으로 캡처한다. 이 파일들의 시간·건수는 로컬 장애 주입 경로를 검증한 값이며, 정식 부하 성능은 공통 AWS 측정일에 기록한다.
