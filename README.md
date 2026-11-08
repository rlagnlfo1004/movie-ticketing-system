# 좌석 지정 영화 예매 시스템

한 상영의 1,000석을 여러 사용자가 동시에 고를 때 중복 예매를 막는 시스템이다. 대기열 입장 후 좌석을 선점하고, 결제 상태를 확인해 예매를 확정한다. 현재 구현 단계는 **V3: Redis 좌석 상태 + Pub/Sub 기반 DB 적재**다. 이후 내구성 개선 단계는 [작업 지도](https://github.com/rlagnlfo1004/movie-ticketing-system/issues/2)에서 진행한다.

## 현재 흐름

1. `waiting-api`가 Redis 대기열 등록·순번 조회·입장을 처리한다.
2. `reservation-api`가 waiting-api에 입장 상태를 확인한다.
3. 사용자가 1~4석을 Redis Lua 한 번으로 선점한다. 한 좌석이라도 사용할 수 없으면 전체 요청이 실패한다.
4. 서버가 결제를 모의 PG에 사전 등록한다. 승인 뒤에는 서명된 웹훅과 클라이언트 완료 호출 중 먼저 도착한 경로가 PG 상태·금액을 조회해 Redis에서 예매를 확정한다.
5. 확정 이벤트는 Pub/Sub으로 별도 `reservation-worker`에 전달된다. worker가 100건 또는 50ms마다 MySQL에 적재한다. worker가 죽은 동안의 이벤트는 재전달되지 않는 것이 V3의 확인된 문제다.

V1은 MySQL 좌석 잠금에서 deadlock이 났고, V2는 잠금 순서를 고쳐 상영 행 직렬화가 남았다. V3는 좌석 상태를 Redis로 옮겨 그 직렬화를 피하지만 Pub/Sub의 유실 위험이 생겼다. 단계별 원본은 [`docs/evidence/`](docs/evidence/)에 있다. 측정 전제와 가정은 [`infra/tuning/README.md`](infra/tuning/README.md)와 [`load-test/README.md`](load-test/README.md)에 적었다.

## 로컬 실행

Java 21과 Docker Compose가 필요하다.

```bash
./gradlew :waiting-api:bootJar :reservation-api:bootJar :reservation-worker:bootJar
docker compose up -d --build
bash load-test/v3-smoke.sh
python3 load-test/v3-atomic-smoke.py
python3 load-test/v3-late-payment-smoke.py
```

API는 `http://localhost:8000/api/v1`에서 받는다. 첫 기동에서 상영 `1`의 25행×40열 좌석을 만든다. 모의 PG는 `http://localhost:8082`, Grafana는 `http://localhost:3000`이다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| POST | `/screenings/{sid}/queue/register` | 대기열 토큰 발급 |
| GET | `/screenings/{sid}/queue/status?token=...` | 입장 상태 조회 |
| GET | `/screenings/{sid}/seats` | 좌석 상태 조회 |
| POST | `/screenings/{sid}/holds` | 좌석 선점, `Queue-Token`·`Idempotency-Key` 필수 |
| POST | `/payments/{paymentId}/complete` | PG 조회 후 예매 확정 |
| POST | `/webhooks/portone` | 서명 검증 후 결제 웹훅으로 예매 확정 |
| GET | `/screenings/{sid}/reservations/me` | 내 선점·확정 상태 조회 |

선점 본문은 `{"seats":["A-12","A-13"]}`이고, 응답은 `holdId`, `paymentId`, `amount`, `heldUntil`을 담는다. 좌석 가격은 한 석에 15,000원이다.

## 검증

```bash
./gradlew test
SMOKE=1 SCENARIO=A STAGE=v3-pubsub ./load-test/run.sh
```

로컬 스모크는 기능과 캡처 경로만 확인한다. 정식 처리량·지연 수치는 모든 단계를 같은 AWS 환경에서 실행하는 측정일에 기록한다. 용어는 [`GLOSSARY.md`](GLOSSARY.md), 설계 배경은 [`docs/adr/`](docs/adr/)에 있다.
