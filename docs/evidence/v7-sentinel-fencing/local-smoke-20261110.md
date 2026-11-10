# V7 로컬 Sentinel 장애 검증 (2026-11-10)

환경: 한 컴퓨터의 Docker Compose, Redis 데이터 노드 3개·Sentinel 3개, MySQL·waiting-api 3개·reservation-api·worker·모의 PG. 이 값은 AWS 성능 수치가 아니다.

명령: `python3 load-test/v7-local-smoke.py` (사전에 `docker compose -f docker-compose.yml -f docker-compose.v7.yml up -d ...`).

| 확인 항목 | 결과 |
| --- | --- |
| Sentinel 초기 발견 | replica 2개, 다른 Sentinel 2개 |
| 장애 전 결제 | A-6 선점 → 모의 PG 승인 → 웹훅 확정 |
| primary SIGKILL 뒤 승격 | 4.626초, configuration epoch 6 → 7 |
| 장애 뒤 결제 | A-7 선점 → 모의 PG 승인 → 웹훅 확정 |
| 최종 확정 좌석 | Redis 7석 = MySQL 7석 |
| 이전 primary 복귀 | replica로 재편입, `SET`은 `READONLY` |

별도 로컬 검증에서 공유 PG 상점의 다른 프로젝트 결제 1건을 모의 PG에 만들고 대사를 실행했다. 그 결제는 `PAID`를 유지했고 취소 목록에 들어가지 않았다. 실제 PortOne 채널 검증은 #22에서 진행한다.

한계: 모든 Redis 컨테이너가 한 Docker 호스트에 있으므로 호스트·AZ 장애와 네트워크 분할의 안전성을 입증하지 않는다. `WAIT 1`과 `min-replicas-to-write 1`은 복제 ACK를 요구하지만 Redis의 강한 일관성 보장은 아니다. 정식 승격 시간·지연·좌석 균형은 AWS 측정 후 별도로 기록한다.
