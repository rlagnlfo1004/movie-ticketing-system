# 좌석 지정 영화 예매 시스템

한 상영의 1,000석을 여러 사용자가 동시에 고를 때 중복 예매를 막는 시스템이다. 대기열 입장 후 좌석을 선점하고, 결제 상태를 확인해 예매를 확정한다. 현재 구현 단계는 **V1: MySQL이 좌석 상태의 기준**이다. 이후 Redis 기반 방식은 [작업 지도](https://github.com/rlagnlfo1004/movie-ticketing-system/issues/2)에서 진행한다.

## 현재 흐름

1. `waiting-api`가 Redis 대기열 등록·순번 조회·입장을 처리한다.
2. `reservation-api`가 waiting-api에 입장 상태를 확인한다.
3. 사용자가 1~4석을 한 번에 선점한다. 한 좌석이라도 사용할 수 없으면 전체 요청이 실패한다.
4. 모의 PG가 결제를 승인하면 클라이언트 완료 호출이 금액과 상태를 조회한 뒤 예매를 확정한다. V1에서는 앱이 완료 호출 전에 닫히면 확정이 누락된다.
5. 30초가 지난 미확정 선점은 스케줄러가 풀어 좌석을 다시 사용할 수 있게 한다.

V1은 요청 좌석 순서대로 MySQL 행을 잠근 뒤 상영 행을 갱신한다. 좌석 순서 역전과 상영 FK의 S→X 잠금 전환이 deadlock을 만들 수 있다. 로컬 재현의 원본은 [`docs/evidence/v1-db/`](docs/evidence/v1-db/)에 있다. 측정 전제와 가정은 [`infra/tuning/README.md`](infra/tuning/README.md)와 [`load-test/README.md`](load-test/README.md)에 적었다.

## 로컬 실행

Java 21과 Docker Compose가 필요하다.

```bash
./gradlew :waiting-api:bootJar :reservation-api:bootJar
docker compose up -d --build
bash load-test/v1-smoke.sh
bash load-test/v1-contract-smoke.sh
bash load-test/v1-expiry-smoke.sh
```

API는 `http://localhost:8000/api/v1`에서 받는다. 첫 기동에서 상영 `1`의 25행×40열 좌석을 만든다. 모의 PG는 `http://localhost:8082`, Grafana는 `http://localhost:3000`이다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| POST | `/screenings/{sid}/queue/register` | 대기열 토큰 발급 |
| GET | `/screenings/{sid}/queue/status?token=...` | 입장 상태 조회 |
| GET | `/screenings/{sid}/seats` | 좌석 상태 조회 |
| POST | `/screenings/{sid}/holds` | 좌석 선점, `Queue-Token`·`Idempotency-Key` 필수 |
| POST | `/payments/{paymentId}/complete` | PG 조회 후 예매 확정 |
| GET | `/screenings/{sid}/reservations/me` | 내 선점·확정 상태 조회 |

선점 본문은 `{"seats":["A-12","A-13"]}`이고, 응답은 `holdId`, `paymentId`, `amount`, `heldUntil`을 담는다. 좌석 가격은 한 석에 15,000원이다.

## 검증

```bash
./gradlew test
SMOKE=1 SCENARIO=A STAGE=v1-db ./load-test/run.sh
```

로컬 스모크는 기능과 캡처 경로만 확인한다. 정식 처리량·지연 수치는 모든 단계를 같은 AWS 환경에서 실행하는 측정일에 기록한다. 용어는 [`GLOSSARY.md`](GLOSSARY.md), 설계 배경은 [`docs/adr/`](docs/adr/)에 있다.
