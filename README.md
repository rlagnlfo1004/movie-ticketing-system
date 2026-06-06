# 선착순 영화 예매 시스템

> Redis 기반 대기열과 멱등성 처리를 적용한 선착순 예매 시스템
> `2026.04 ~ 2026.05`

<br>

## 목차

- [프로젝트 소개](#프로젝트-소개)
- [주요 문제 해결](#주요-문제-해결)
- [아키텍처](#아키텍처)
- [선착순 예매 시스템 구현기: Redis 대기열에서 MySQL 티켓 기록까지](#선착순-예매-시스템-구현기-redis-대기열에서-mysql-티켓-기록까지)
  - [1. 대기열 등록: Redis ZSET으로 먼저 줄 세우기](#1-대기열-등록-redis-zset으로-먼저-줄-세우기)
  - [2. 상태 폴링: RDB를 보지 않는 고빈도 조회](#2-상태-폴링-rdb를-보지-않는-고빈도-조회)
  - [3. Active 사용자 입장: 스케줄러로 속도를 제한하기](#3-active-사용자-입장-스케줄러로-속도를-제한하기)
  - [4. 티켓 발급: active 검증 뒤에만 재고를 건드리기](#4-티켓-발급-active-검증-뒤에만-재고를-건드리기)
  - [5. 멱등성: 비즈니스 로직 앞단의 문지기](#5-멱등성-비즈니스-로직-앞단의-문지기)
  - [6. 원자적 재고 차감: Redis Lua 한 번으로 결정하기](#6-원자적-재고-차감-redislua한번으로결정하기)
  - [7. Durable Ticket Record: 최종 진실은 MySQL에 남기기](#7-durable-ticket-record최종진실은mysql에남기기)
- [전체 흐름에서의 경계 나누기](#전체흐름에서의경계나누기)
- [검증 과정 및 테스트](#검증-과정-및-테스트)
  - [부하 테스트 환경 최적화](#1-부하-테스트-환경-최적화)
  - [k6 부하 테스트 결과](#2-k6-부하-테스트-결과)
- [마무리](#마무리)

<br>

## 프로젝트 소개

선착순 예매 환경에서 순간적인 대량 요청은 **서버 과부하**, **중복 요청**, **재고 초과 차감** 문제를 유발할 수 있습니다.

이를 해결하기 위해 **Redis 기반 대기열**, **Idempotency-Key**, **Redis Lua Script**를 활용해 예매 요청 제어·중복 요청 차단·원자적 재고 차감 구조를 설계했습니다.

k6 부하 테스트에서 **초당 30,000건** 규모의 요청 상황에서도 재고 정합성이 유지됨을 검증했으며, 최소 비용(Nginx, Spring Boot, Redis, MySQL)으로 구성 가능한 아키텍처를 구현했습니다.

<br>

## 주요 문제 해결

### 1. Redis ZSET 기반 대기열과 예매 진입 제어

**문제**

예매 시작 시 대량 요청이 예매 API와 DB로 직접 유입되며 서버 과부하와 응답 지연 발생.

**해결**

- Redis ZSET 기반 대기열과 Scheduler 기반 입장 제어 구조를 구성해, 처리 가능한 사용자만 예매 API에 진입하도록 제한
- `ZRANK` · `ZCARD` · `ZPOPMIN` 기반 순번 조회 및 사용자 승격 구조로 예매 요청 유입량 제어
- 스케줄러 인스턴스 간 중복 실행 방지를 위해 screening별 Redis 분산 락 적용

```
사용자 요청 → 대기열 등록(ZSET) → Scheduler가 배치 승격 → active 토큰 발급 → 예매 API 진입 허용
```

<br>

### 2. Idempotency-Key 기반 중복 요청 차단

**문제**

브라우저 중복 클릭·네트워크 재시도로 동일 예매 요청이 비즈니스 로직까지 반복 진입하며 재고 차감과 예매 저장 중복 실행 위험 존재.

**해결**

- Redis `SETNX EX` 기반 Idempotency-Key 검증 구조를 Filter 계층에 적용하여 중복 예매 요청 방지
- HTTP method·URI·대기열 UUID·정규화 JSON Body 기반 fingerprint를 생성해 동일 키의 충돌 요청 방지

```
요청 수신 → fingerprint 생성 → Redis SETNX 시도 → 이미 존재하면 409 반환 / 없으면 처리 진행
```

<br>

### 3. Redis Lua Script 기반 원자적 재고 차감

**문제**

재고 확인과 차감이 분리되면 동시 요청 상황에서 초과 발급 가능성 존재.

**해결**

- Redis Lua Script 하나로 **재고 확인 → 차감 → 중복 발급 방지** 처리를 원자적으로 수행
- Redis는 빠른 재고 판단과 트래픽 제어를 담당하고, MySQL은 최종 예매 이력을 보장하도록 책임 분리

```lua
-- 단일 Script 내 원자적 실행
if 재고 존재 and 미발급 사용자
  then 재고 차감 + 발급 기록
  else 실패 반환
```

<br>

## 아키텍처

<img width="4050" height="1340" alt="Image" src="https://github.com/user-attachments/assets/8f011705-2be4-47ab-9398-bbb75393e1a9" />

<br>

## 선착순 예매 시스템 구현기: Redis 대기열에서 MySQL 티켓 기록까지

1. 사용자가 예매 페이지에 들어오면 Redis 대기열에 토큰을 등록한다.
2. 클라이언트는 폴링으로 자신의 대기 상태를 확인한다.
3. 스케줄러는 예약 서버가 감당 가능한 속도로 사용자를 active 상태로 승격한다.
4. active 사용자만 예매 API에 진입한다.
5. 예매 API는 멱등성 게이트를 먼저 통과시킨다.
6. Redis Lua로 재고를 원자적으로 차감한다.
7. 성공한 발급 결과는 MySQL에 영속 기록으로 남긴다.

<br>

<details>
<summary><h3>1. 대기열 등록: Redis ZSET으로 먼저 줄 세우기</h3></summary>

예매 페이지에 진입한 사용자는 `Queue-Token`을 받는다. 클라이언트가 토큰 없이 요청하면 서버가 UUID를 발급하고, 이미 토큰이 있으면 그 토큰으로 다시 등록한다. 저장 위치는 Redis sorted set이고, key는 screening 단위로 나눈다.

```text
waiting:screening:{screeningId}:queue
```

- member는 queue token, score는 등록 시각의 epoch millis다. 이 구조를 선택한 이유는 선착순 예매에서 필요한 연산이 sorted set과 잘 맞기 때문이다. 현재 대기자 수는 `ZCARD`, 내 순번은 `ZRANK`, 다음 입장자는 `ZPOPMIN`으로 구할 수 있다. 즉, 대기열 등록 이후의 기능들이 같은 자료구조 위에서 자연스럽게 이어진다.
- 중복 등록은 `ZADD NX`로 처리했다. 같은 토큰이 다시 들어와도 기존 member를 유지하고 score를 갱신하지 않는다. 네트워크 재시도나 브라우저 새로고침 때문에 같은 사용자가 다시 등록되더라도, 뒤늦은 재요청이 등록 시간을 바꾸면 순서가 흔들린다. `ZADD NX`는 Redis 단일 명령으로 원자성을 보장하면서 최초 등록 순서를 보존한다.
- Redis List는 `LPUSH`/`RPOP` 같은 큐 연산이 단순하지만, member 기준 멱등성을 자연스럽게 제공하지 않는다. 중복 제거를 위해 별도 SET을 두면 자료구조가 늘어나고 두 자료구조 사이의 원자성 문제가 생긴다. Redis Stream은 소비자 그룹 같은 기능이 강하지만, 여기서는 메시지 처리보다는 "현재 몇 번째인가"가 더 중요했다. MySQL에 대기열을 넣는 방법도 가능하지만, 이 기능의 목표는 예매 저장소를 보호하는 것이므로 대기 진입부터 RDB에 부하를 주는 선택은 맞지 않다고 생각했다.

</details>

<details>
<summary><h3>2. 상태 폴링: RDB를 보지 않는 고빈도 조회</h3></summary>

대기 화면은 사용자가 가장 자주 호출하는 API다. 그래서 상태 폴링은 Redis만 조회하도록 설계했다. 클라이언트는 토큰으로 현재 상태를 묻고, 서버는 `WAITING`, `ACTIVE`, `NOT_FOUND` 중 하나를 반환한다.

```text
1. waiting:screening:{screeningId}:active:{token} 존재 여부 확인
2. active면 ACTIVE 반환
3. active가 아니면 waiting ZSET에서 ZRANK 조회
4. rank가 있으면 WAITING 반환
5. 둘 다 없으면 NOT_FOUND 반환
```

- 상태 판단 순서는 active 확인이 먼저다. 이후 waiting ZSET의 rank를 본다.
- `pollAfterMillis`도 응답에 포함했다. 클라이언트가 임의로 너무 짧은 주기로 폴링하지 않도록 서버가 pacing 정책을 내려주는 방식이다.
- TTL이 만료된 active token은 `NOT_FOUND`로 본다. `EXPIRED`를 따로 반환하려면 만료 흔적을 남기기 위한 별도의 record가 필요하다. 하지만 이 단계의 목표는 고빈도 상태 조회를 단순하고 빠르게 유지하는 것이다. 만료와 존재하지 않음을 구분하는 UX 가치보다 쓰기 경로와 저장 구조가 복잡해지는 비용이 더 크다고 판단했다.

</details>

<details>
<summary><h3>3. Active 사용자 입장: 스케줄러로 속도를 제한하기</h3></summary>

대기열이 있다고 해서 한 번에 많은 사용자를 예매 API로 보내면 병목이 reservation-api로 옮겨갈 뿐이다. 그래서 active 사용자 전환은 스케줄러가 맡는다. 설정된 주기마다 screening별로 최대 batch size만큼 사용자를 꺼내 active 상태로 만든다.

구현의 핵심은 세 가지다.

1. 첫째, screening별 Redis lock을 잡는다. 여러 인스턴스가 동시에 스케줄러를 실행해도 한 인스턴스만 해당 screening의 입장 처리를 수행해야 한다. `SET NX PX` 방식의 lock을 통해 TTL을 통해 인스턴스 장애 시 영구 lock을 피한다. JVM `synchronized`는 단일 프로세스에서만 의미가 있고, DB lock은 Redis-only 대기열 경로에 MySQL 의존성을 추가한다.
2. 둘째, capacity를 먼저 계산하고 그만큼만 pop한다. active 사용자 수를 세고, `maxActiveUsers - activeCount`와 `batchSize` 중 작은 값을 구한 뒤 그 수만큼 `ZPOPMIN`한다. capacity가 0이면 대기열을 건드리지 않는다. 먼저 batch만큼 pop한 뒤 초과분을 되돌리는 방식은 실패 처리 중 순서가 바뀔 수 있고, 구현도 더 복잡하다.
3. 셋째, active 권한은 TTL key와 active index를 함께 사용한다.

```text
waiting:screening:{screeningId}:active:{token}
waiting:screening:{screeningId}:active:index
```

- TTL key는 입장 권한의 만료를 표현한다. 하지만 TTL key만 있으면 현재 active 수를 효율적으로 세기 어렵다. Redis key pattern scan은 운영 부하와 정확성 측면에서 부담이 있다. 그래서 screening-scoped active index를 함께 두고, count 전에 만료된 token을 pruning하는 구조로 갔다.
- 여기에도 trade-off가 있다. TTL key와 index라는 두 상태를 관리해야 하므로 정리 로직이 필요하다. 대신 capacity guard를 빠르게 수행할 수 있고, active validation도 단순해진다.
- 스케줄러 결과는 로그와 Micrometer metric으로 남긴다. 선착순 시스템에서 "왜 입장이 안 되었는지"는 기능만큼 중요하다. lock 획득 실패, capacity hit, promotion 성공/실패가 관측되지 않으면 운영 중 병목 위치를 알기 어렵다.

</details>

<details>
<summary><h3>4. 티켓 발급: active 검증 뒤에만 재고를 건드리기</h3></summary>

티켓 발급 API는 active 사용자만 통과시킨다. 발급 요청은 quantity 1만 허용하고, `Queue-Token`과 `Idempotency-Key`를 요구한다. 흐름은 다음 순서로 정리했다.

```text
1. Queue-Token 검증
2. active admission 검증
3. Idempotency-Key 검증
4. quantity = 1 검증
5. Redis Lua로 재고 차감 및 issued marker 기록
6. MySQL에 티켓 발급 결과 저장
7. DTO 응답 반환
```

- 재고를 차감하기 전에 active admission을 확인한 이유는 명확하다. 아직 입장하지 못한 사용자는 reservation-api의 비싼 경로에 들어오면 안 된다. 클라이언트가 "나는 active다"라고 보내는 값은 신뢰할 수 없으므로 서버가 저장소 기준으로 확인한다.
- 중복 발급 방지는 Redis marker와 MySQL unique constraint를 함께 사용했다. Redis marker는 빠른 중복 차단에 유리하고, MySQL unique constraint는 최종 durable guard다.
- 에러 응답에는 별도의 error code를 둔다. HTTP status만으로는 `INACTIVE_ADMISSION`, `DUPLICATE_TICKET`, `TICKET_SOLD_OUT`, `IDEMPOTENCY_KEY_CONFLICT`, `PERSISTENCE_PENDING` 같은 비즈니스 실패를 충분히 구분하기 어렵다. 클라이언트는 code를 기준으로 사용자 메시지와 재시도 정책을 결정할 수 있다.


이떄, 가장 까다로운 부분은 **Redis 차감 성공 후 MySQL 저장 실패다.** 이 경우 이미 한정된 재고는 소비되었지만 durable ticket은 없다. 즉시 Redis 재고를 되돌리는 방법도 있지만, DB commit이 실제로 성공했는데 애플리케이션이 실패로 관측한 애매한 경우에는 보상이 오히려 초과 발급 가능성을 만든다. 그래서 `PERSISTENCE_PENDING` 기록을 남기고 재처리 대상으로 다루는 쪽을 선택했다. 보수적으로 재고의 경계를 지키고, 운영자가 추적 가능한 복구 경로를 갖는 방식이다.

</details>

<details>
<summary><h3>5. 멱등성: 비즈니스 로직 앞단의 문지기</h3></summary>

네트워크 재시도, 브라우저 중복 클릭, 클라이언트 타임아웃은 모두 같은 요청을 여러 번 보낼 수 있다. 이때 "티켓은 하나만 발급된다"를 보장하려면 예매 서비스 안쪽에서 중복을 막는 것만으로 부족하다. 재고 차감이나 active 검증 같은 비즈니스 로직에 들어가기 전에 같은 요청을 걸러야 한다.
그래서 멱등성 검사는 Spring `OncePerRequestFilter`에 배치했다. filter는 controller binding보다 앞에서 실행되므로 `Idempotency-Key`가 없거나 충돌하는 요청을 예매 로직 진입 전에 차단할 수 있다. `HandlerInterceptor`도 후보였지만 request body를 안정적으로 읽고 canonical fingerprint를 만들기에는 filter가 더 적합했다. service 내부에서 검사하는 방식은 구현은 간단하지만, acceptance condition인 "멱등성 실패 시 재고 차감이 실행되지 않는다"를 강하게 보장하기 어렵다.
멱등성 record는 Redis에 `SET key value NX EX`로 claim한다.

```text
idempotency:{screeningId}:{idempotencyKey}
```

- fingerprint는 method, normalized URI, queue token, canonical JSON body로 만든다. raw body hash는 간단하지만 JSON 공백이나 필드 순서 차이만으로 다른 요청으로 오인할 수 있다. 반대로 body만 hash하면 같은 key가 다른 endpoint나 다른 token에 재사용되는 문제를 놓친다. canonical JSON을 사용하면 의미상 같은 요청은 같게 보고, 실제로 다른 요청은 충돌로 볼 수 있다.
- 동일 fingerprint의 중복 요청이 아직 진행 중이면 `409 CONFLICT`를 반환한다. 완료까지 기다렸다가 결과를 replay하는 방식도 가능하지만, 그 방식은 servlet thread를 오래 붙잡고 응답 캐시까지 필요하다. 이 feature의 목적은 "동시에 하나만 진입"시키는 것이므로 in-progress 중복은 빠르게 실패시키는 쪽을 택했다. 완료된 durable ticket에 대한 재조회나 replay는 downstream의 ticket issuance guard가 맡을 수 있다.

</details>

<details>
<summary><h3>6. 원자적 재고 차감: Redis Lua 한 번으로 결정하기</h3></summary>

재고 차감은 "확인"과 "차감"이 분리되는 순간 race가 생긴다. 동시에 두 요청이 stock 1을 읽고 둘 다 차감하면 초과 발급이 발생한다. 그래서 재고 처리는 Redis Lua script 하나로 묶었다.

Lua script는 다음 판단을 한 번에 수행한다.

```text
1. inventory key가 존재하는지 확인
2. 값이 정수이며 0보다 큰지 확인
3. issued-token marker가 이미 있는지 확인
4. 재고를 1 감소
5. issued-token marker 기록
6. 결과 code 반환
```

- Redis는 Lua script 실행 중 다른 명령을 끼워 넣지 않으므로 이 판단은 원자적이다. Java에서 `GET` 후 `DECR`을 호출하는 방식은 가장 단순하지만 race가 있다. Redis `WATCH` transaction도 가능하지만 충돌 시 retry loop를 클라이언트가 관리해야 한다. 단일 key 중심의 원자 판단에는 Lua가 더 직접적이었다.
- issued-token marker도 Lua 안에서 함께 기록한다. marker를 Java에서 decrement 이후에 쓰면, 차감 성공 후 marker 쓰기 실패라는 또 다른 불일치가 생긴다. marker를 DB에만 의존하면 중복 요청이 DB에서 막히기 전에 Redis stock을 소비할 수 있다. 재고 차감과 중복 marker 기록을 같은 script에 넣은 이유다.

</details>

<details>
<summary><h3>7. Durable Ticket Record: 최종 진실은 MySQL에 남기기</h3></summary>

Redis는 빠른 판단에는 좋지만 최종 이력 저장소로 쓰기에는 한계가 있다. 티켓 발급 성공 결과는 MySQL의 durable record로 남긴다.

- ticket table에는 `(screening_id, queue_token)` unique constraint를 둔다. application pre-check는 더 친절한 에러 메시지를 줄 수 있지만, 동시성에서 최종 보장은 DB constraint가 맡아야 한다. 같은 queue token으로 durable ticket이 두 개 생기지 않는다는 요구사항은 저장소 레벨에서 막아야 한다.
- API 응답에는 JPA entity를 직접 노출하지 않는다.
- Redis 성공 후 MySQL 실패는 여기서도 `PersistencePendingIssuance`로 남긴다. 이 정책은 즉시 보상보다 보수적이다. 재고를 즉시 증가시키면 사용자는 다시 발급 기회를 얻을 수 있지만, DB 실패가 모호할 때는 초과 발급 위험이 커진다. pending record는 운영자와 재처리 작업이 볼 수 있는 명시적 흔적을 남긴다.

</details>

<br>

## 전체 흐름에서의 경계 나누기

이번 구현에서 가장 중요한 설계 기준은 "어떤 저장소가 어떤 책임을 갖는가"였다.
  - Redis는 대기열, active admission, idempotency in-progress gate, 원자적 재고 차감처럼 빠르고 짧은 판단을 맡는다. Redis 명령의 원자성, TTL, sorted set ordering, Lua script를 적극적으로 사용했다.
  - MySQL은 screening, ticket issuance, persistence pending 같은 durable record를 맡는다. 최종 중복 방지는 unique constraint로 보장한다.


각 모듈에 대해서,
  - waiting-api는 트래픽 제어의 주인이다. 등록, 순번 조회, active 승격, active validation을 처리한다.
  - reservation-api는 실제 발급의 주인이다. active 검증을 통과한 사용자에 대해 멱등성, 재고 차감, ticket persistence를 수행한다.
  - 이 경계는 완벽한 정답이라기보다 현재 요구사항에 맞춘 선택이다. 예를 들어 active validation을 reservation-api가 Redis에서 직접 읽으면 latency는 줄지만 서비스 간 결합이 커진다. waiting-api endpoint를 호출하면 계약은 명확하지만 runtime dependency와 network hop이 생긴다.


마지막으로, Redis 재고 차감 후 MySQL 실패를 즉시 보상하면 회복은 빨라 보이지만 ambiguous commit 상황에서 초과 발급 위험이 생긴다. pending record는 즉시 사용자 성공을 만들지는 못하지만, 한정 재고를 보수적으로 지킨다.

<br>

## 검증 과정 및 테스트

k6를 사용해 **30,000명이 5,000 VU로 동시에 접속**하는 시나리오를 구성했다. 각 VU는 대기열 등록 → 상태 폴링 → 티켓 발급까지 전체 플로우를 수행하며, maxDuration은 15분으로 설정했다.

검증의 핵심은 재고 정합성이다. 티켓 수는 1,000장으로 고정하고, 30,000명이 동시에 시도하더라도 정확히 1,000장만 발급되는지를 확인했다.

<br>

<details>
<summary><h3>1. 부하 테스트 환경 최적화</h3></summary>

k6 부하 테스트를 로컬 Docker 환경에서 실행하기 위해 여러 계층에 걸쳐 튜닝을 진행했다.

**Nginx**

- `worker_processes auto` — CPU 코어 수에 맞춰 worker 자동 설정
- `worker_connections 10240` — worker당 최대 동시 연결 수 확보
- `use epoll`, `multi_accept on` — Linux 이벤트 모델 활용으로 연결 처리 효율화
- waiting-api 3개 인스턴스에 `least_conn` 방식 로드 밸런싱 적용
- `keepalive 100` (waiting-api), `keepalive 32` (reservation-api) — upstream 커넥션 재사용으로 TCP handshake 오버헤드 제거

**Redis**

- `--maxclients 10000`, `--tcp-backlog 511` — 대량 동시 연결 수용
- `--hz 20` — 내부 타이머 정밀도 향상
- `--save ""` — 디스크 스냅샷 비활성화로 I/O 부하 제거
- `commons-pool2` 의존성 추가 → Lettuce connection pool 활성화 (`max-active 200`, `max-idle 100`, `min-idle 50`)

**MySQL**

- `innodb-buffer-pool-size` 1G → 3G — 데이터 페이지 캐시 확대로 디스크 I/O 감소
- `innodb-log-file-size` 256M → 512M — 대량 쓰기 시 로그 플러시 빈도 감소
- `innodb-flush-log-at-trx-commit=2` — 트랜잭션 커밋마다 fsync 생략, 성능과 내구성 균형
- `max-connections 500`

**JVM (waiting-api × 3, reservation-api)**

- `-Xms512m -Xmx2g` — 힙 범위 고정으로 런타임 재할당 방지
- `-XX:+UseG1GC -XX:MaxGCPauseMillis=50` — GC pause 목표값 설정
- `SPRING_THREADS_VIRTUAL_ENABLED: true` — Java 21 가상 스레드 활성화로 블로킹 I/O 처리 효율화

**HikariCP (reservation-api)**

- `maximum-pool-size 100`, `minimum-idle 20` — DB 커넥션 풀 사전 확보
- `connection-timeout 3000ms` — 느린 DB 연결 조기 감지

**Admission Scheduler**

- `scheduler-interval 1000ms` — 1초 주기로 입장 처리
- `batch-size 300` — 회당 최대 300명 승격 (초당 최대 300 req/s 유입 제어)
- `max-active-users 1000` — 총 티켓 수(1,000)와 일치시켜 불필요한 대기 최소화

**DB 인덱스**

- `tickets` 테이블에 `(screening_id, issued_at DESC)` 복합 인덱스 추가 — 티켓 이력 조회 쿼리 커버
- `(screening_id, queue_token)`, `(screening_id, idempotency_key)` unique constraint — 핫패스 중복 검사 쿼리 인덱스로 활용

</details>

<details>
<summary><h3>2. k6 부하 테스트 결과</h3></summary>

**시나리오**: 30,000명이 5,000 VU로 대기열 등록 → 상태 폴링 → 티켓 발급까지 전체 플로우를 수행

**핵심 정합성**

| 항목 | 결과 | 설명 |
|---|---|---|
| tickets_issued | **1,000장** | 설정된 재고와 정확히 일치. 초과 발급 없음 |
| tickets_sold_out | 11,277건 | 재고 소진 후 뒤늦게 ACTIVE로 승격된 사용자들이 발급을 시도했으나 409 반환. 중복이 아닌 정상적인 선착순 탈락 |
| queue_register_errors | **0%** | 5,000명 동시 burst에서 등록 실패 없음 |
| ticket_issue_errors | **0%** | SOLD_OUT(409)은 에러로 집계하지 않음. 비즈니스 로직 오류 0건 |
| 전체 checks | 24,129 pass / 0 fail | 등록 200 응답(23,129) + 발급 201 응답(1,000) 모두 통과 |

**응답 성능**

| 지표 | 설명 | avg | p90 | p95 | max |
|---|---|---|---|---|---|
| http_req_duration | 개별 HTTP 요청 1건의 전체 왕복 시간 (전송 + 서버 처리 + 수신). 폴링이 전체 요청의 대부분을 차지하므로 낮게 측정됨 | 10.3ms | 8.2ms | 18.7ms | 4,093ms |
| queue_register_duration | 대기열 등록 요청을 보낸 시점부터 응답을 받은 시점까지의 시간. 30,000명 동시 burst에서 Redis ZSET 경합이 반영됨 | 1,280ms | 3,728ms | 4,052ms | 4,452ms |
| ticket_issue_duration | ACTIVE 상태 확인 후 티켓 발급 요청을 보낸 시점부터 응답을 받은 시점까지의 시간. active 검증 → Lua 재고 차감 → MySQL 저장 경로를 포함 | 256ms | 1,096ms | 1,339ms | 3,135ms |

총 HTTP 요청 **4,592,687건** (대부분 상태 폴링)

**결과: 재고 정합성 검증**

| tickets 실제 발급 수 (1000) | screenings 설정된 재고 수 (1000) |
| --- | --- |
| <img width="2616" height="1000" alt="Image" src="https://github.com/user-attachments/assets/feb1e744-1661-47bb-8c46-fedf8777addc" /> | <img width="538" height="176" alt="Image" src="https://github.com/user-attachments/assets/e38d420a-9685-4235-b26d-b27dffac3b27" /> |

</details>

<br>

## 마무리

이 설계는 선착순 예매를 하나의 거대한 transaction으로 풀지 않았다. 대신 트래픽 제어, 입장 권한, 멱등성, 재고 차감, 영속 기록을 작은 결정들로 나누었다. Redis와 MySQL을 모두 사용하지만 같은 책임을 두 번 맡기지는 않았다. Redis는 빠른 gatekeeper이고, MySQL은 durable source of truth다.

결국 선착순 예매에서 중요한 것은 "빠르게 성공시키는 것"만이 아니다. 실패해야 하는 요청을 일찍 실패시키고, 성공한 요청은 중복 없이 남기며, 애매한 실패는 운영자가 복구할 수 있는 흔적으로 남기는 것이다. 이번 spec들은 그 기준을 따라 기능을 쌓아 올린 구현 기록이다.
