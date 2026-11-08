# PortOne V2 테스트 채널과 웹훅 조사

- 조사일: 2026-11-08
- 질문: GitHub issue #4 (지도 #2의 하위 조사 티켓)
- 출처 원칙: PortOne 개발자센터 문서(`developers.portone.io`), PortOne이 공개한 스키마 파일(V2 OpenAPI, 브라우저 SDK 스키마), PortOne 공식 GitHub 저장소(`portone-io/server-sdk`), PortOne이 웹훅 검증 근거로 명시한 Standard Webhooks 스펙만 사용했다.
- 표기: 각 사실 뒤에 근거를 [S번호]로 적었다. 출처에 없는 내용은 「확인 못 함」으로 따로 적었다. 추측은 쓰지 않았고, 출처에서 끌어낸 해석은 「해석」으로 표시했다.

## 요약

- 테스트 결제는 콘솔에서 연동 모드를 `테스트`로 바꿔 채널을 추가하면 할 수 있다. 결제창 호출에는 Store ID와 채널 키가, 서버 API 호출에는 V2 API Secret이, 웹훅 검증에는 테스트 모드용 웹훅 시크릿이 필요하다. [S2][S3][S4]
- 웹훅 최신 버전(`2024-04-25`)의 본문은 `{type, timestamp, data}` 형태이고, 결제 이벤트 이름은 `Transaction.Paid`, `Transaction.Cancelled` 등이다. 헤더는 Standard Webhooks의 `webhook-id`, `webhook-timestamp`, `webhook-signature`이며 HMAC-SHA256으로 서명한다. JVM에서는 `io.portone:server-sdk`의 `WebhookVerifier`가 검증한다. [S1][S9][S10]
- 웹훅이 실패하면 최대 5회 다시 보낸다. 대기 시간은 0 → 1 → 4 → 16 → 64 → 256분에 jitter를 더한 값이다. 연결 시간 제한과 읽기 시간 제한은 모두 30초다. [S1][S8]
- 결제 단건 조회는 `GET https://api.portone.io/payments/{paymentId}`, 결제 취소는 `POST https://api.portone.io/payments/{paymentId}/cancel`이다. 인증 헤더는 `Authorization: PortOne {API_SECRET}`이다. 취소 금액(`amount`)을 비우면 전액 취소된다. [S5][S6][S7]
- 결제창 유효 시간을 정하는 공통 파라미터는 없다. PG별 `bypass`(예: 토스페이 `expiredTime`, 기본 15분 · 최대 60분)나 가상계좌 입금 기한만 있다. [S11][S12]
- 웹훅 전달 지연 시간에 대한 수치는 공식 문서 어디에도 없다. 대신 결제 조회 응답의 웹훅 발송 내역(`webhooks[]`)에 요청 시각과 응답 시각이 남으므로 이 값으로 직접 잴 수 있다. [S5]

## 1. 테스트 채널(테스트 모드)

### 확인된 사실

- 전자결제 신청 전에도 콘솔 `결제 연동` 페이지에서 테스트 채널을 추가해 결제를 시험할 수 있다. [S3][S13]
- 테스트 채널 추가 절차는 다음과 같다. [S2][S3]
  1. 연동 모드를 `테스트`로 바꾼다.
  2. 결제대행사(PG)와 결제 모듈을 고른다. V2를 쓰려면 V2용 모듈을 골라야 한다. 예를 들어 토스페이먼츠는 「토스페이먼츠(신모듈)」로 연동해야 V2 결제 모듈을 쓸 수 있다. [S14]
  3. 테스트용으로 받은 상점 아이디(MID)를 넣거나, 콘솔이 제공하는 공용 상점 아이디를 고른다. 공용 테스트 채널이 있는 PG는 MID를 고르면 채널 이름이 자동으로 채워진다.
  4. 저장하면 채널이 생기고, 채널을 연동할 때 **채널 키**가 생성된다. [S14]
- 결제창 호출(`PortOne.requestPayment()`)에는 **Store ID**(`storeId`)와 **채널 키**(`channelKey`)가 필요하다. 둘 다 콘솔의 결제 연동 화면에서 확인한다. [S4]
- **V2 API Secret**은 콘솔 `[결제연동] → [연동 관리] → [식별코드·API Keys] → [V2 API]`에서 발급한다. Owner와 Admin 계정만 발급할 수 있고, 발급할 때 만료 기한을 고른다. 값은 발급 직후에만 볼 수 있다. 서버에서만 써야 한다. [S2]
- **웹훅 URL과 웹훅 시크릿은 실연동과 테스트 모드에 따로 설정**한다. 웹훅 시크릿은 모드마다 동시에 최대 2개까지 발급할 수 있다(무중단 교체용). [S1]
- 결제 조회 응답의 `channel.type` 값은 `LIVE` 또는 `TEST`다. 그래서 응답만 보고 테스트 결제인지 가릴 수 있다. [S5]
- 콘솔 결제 내역에서 테스트 결제는 「테스트 결제」 필터를 켜야 보인다. 이 필터는 기본으로 꺼져 있다. [S15]
- REST API의 서버 주소로 OpenAPI 스키마에 적힌 것은 `https://api.portone.io` 하나뿐이다. [S5]

### PG별 테스트 제약 (확인된 것만)

- 토스페이먼츠 테스트 MID로 간편결제를 호출하면 테스트가 원활하지 않을 수 있다. 간편결제는 토스페이먼츠와 사전 계약이 필요하며, `[PAY_PROCESS_ABORTED] ... 계약된 결제수단(NAVERPAY)이 아닙니다` 같은 오류가 나면 실 상점 아이디로 계약한 뒤에 쓸 수 있다고 문서에 적혀 있다. [S14]
- 스마트로 테스트 채널에서는 카카오페이 결제가 자동 취소되지 않는다. 테스트 결제 건은 콘솔이나 결제 취소 API로 직접 취소해야 한다. 같은 PG의 테스트 채널에서 체크카드 결제는 전액 취소만 되고, 국민카드와 카카오뱅크 카드는 결제가 안 된다. [S16]
- 카카오페이(V2)는 PC에서 `IFRAME`, 모바일에서 `REDIRECTION` 방식만 지원한다. `payMethod`는 `EASY_PAY`다. [S17]

### 확인 못 함

- 테스트 채널 결제에서 실제 출금이 일어나는지, 일어난다면 PG가 자동으로 취소하는지는 PG마다 다르다. PortOne이 모든 PG에 공통으로 적용되는 규칙을 문서로 밝힌 곳은 찾지 못했다.
- API Secret을 테스트와 실연동에 따로 발급하는지는 문서에 나오지 않는다. 웹훅 URL과 웹훅 시크릿은 모드별로 따로 설정한다고 적혀 있지만, API Secret 발급 절차에는 모드를 고르는 단계가 없다. [S1][S2] 해석: 테스트와 실연동의 구분은 Secret이 아니라 채널이 정하는 것으로 보인다(응답의 `channel.type`, 수동 승인 API의 `isTest` 검증 파라미터 [S5]).
- 공용 테스트 채널을 제공하는 PG의 전체 목록은 문서에 표로 나와 있지 않다.

## 2. 웹훅 형식

### 웹훅 버전과 콘솔 설정

- 웹훅 버전은 `2024-04-25`(최신)와 `2024-01-01` 두 가지다. 받을 버전은 콘솔에서 고른다. `2024-04-25`부터 본문 형식이 Standard Webhooks를 따른다. [S1]
- 설정 경로: 콘솔 `[결제 연동] → [연동 관리] → [결제알림(Webhook) 관리]` 탭에서 `[웹훅 버전]`은 `[결제모듈 V2]`, `[설정 모드]`는 `[실연동]` 또는 `[테스트]`를 고르고, Endpoint URL과 Content Type을 넣어 저장한다. `2024-04-25` 버전은 Content Type으로 `application/json`만 지원한다. [S1]
- `호출 테스트` 버튼을 누르면 **저장된** URL로 시험 웹훅이 간다. URL을 바꾼 뒤 저장하지 않으면 이전 주소로 간다. [S1]
- 결제 요청 때 `noticeUrls` 파라미터를 넘기면 콘솔에 설정한 URL 대신 그 URL로 웹훅이 간다. [S1][S11]
- 웹훅 URL 삭제는 기술지원 메일로 요청해야 한다. [S1]

### 본문 (`2024-04-25` 버전)

최상위 필드는 세 개다. [S1]

- `type`: 이벤트 종류.
- `timestamp`: 이벤트가 일어난 시각(RFC 3339). **재시도해도 값이 바뀌지 않는다.**
- `data`: 이벤트 세부 내용. 결제 이벤트는 `storeId`, `paymentId`, `transactionId`를 담는다. 취소 계열(`Transaction.PartialCancelled`, `Transaction.Cancelled`, `Transaction.CancelPending`)은 `cancellationId`도 담는다.

결제 관련 `type` 값은 다음과 같다. [S1][S10]

- `Transaction.Ready`: 결제창이 열렸을 때
- `Transaction.Paid`: 결제(예약 결제 포함)가 승인됐을 때
- `Transaction.VirtualAccountIssued`: 가상계좌가 발급됐을 때
- `Transaction.PartialCancelled`: 부분 취소됐을 때
- `Transaction.Cancelled`: 전액 취소됐을 때
- `Transaction.Failed`: 결제가 실패했을 때
- `Transaction.PayPending`: 결제 승인 대기 상태가 됐을 때(페이팔 등)
- `Transaction.CancelPending`: 비동기 취소에서 취소를 요청했을 때
- `Transaction.DisputeCreated`, `Transaction.DisputeResolved`: 분쟁 발생과 해소
- `Transaction.Confirm`: 컨펌 프로세스를 쓸 때 승인 직전에 고객사 `confirmUrl`로 가는 웹훅. `data`에 `totalAmount`가 더 들어간다. [S18]

빌링키 이벤트(`BillingKey.*`)도 있지만 이 프로젝트에서는 쓰지 않는다.

PortOne은 별도 안내 없이 `type` 값이나 필드를 추가할 수 있다고 밝혔다. 모르는 `type`은 오류를 내지 말고 메시지 전체를 무시하고, 모르는 필드도 무시하라고 안내한다. [S1]

본문 예시(문서 원문의 `Transaction.Cancelled` 예시) [S1]:

```json
{
  "type": "Transaction.Cancelled",
  "timestamp": "2024-04-25T10:00:00.000Z",
  "data": {
    "paymentId": "example-payment-id",
    "storeId": "store-ae356798-3d20-4969-b739-14c6b0e1a667",
    "transactionId": "55451513-9763-4a7a-bb43-78a4c65be843",
    "cancellationId": "0cdd91e9-4e7c-44a3-a72e-1a6511826c2b"
  }
}
```

`Transaction.Paid` 본문은 위 예시에서 `type`만 바꾸고 `cancellationId`를 뺀 모양이다(`data`의 필수 필드는 `paymentId`, `storeId`, `transactionId` [S10]).

### 구버전 (`2024-01-01`)

본문이 `{payment_id, tx_id, status}`이고 `status`는 `Ready`, `Paid`, `Cancelled` 등이다. 새로 연동할 때는 쓸 이유가 없다. [S1]

### 헤더

`webhook-id`, `webhook-timestamp`, `webhook-signature` 세 헤더로 서명이 전달된다. 헤더 이름은 PortOne JVM SDK의 `WebhookVerifier.HEADER_ID`, `HEADER_TIMESTAMP`, `HEADER_SIGNATURE` 상수에서 확인했다. [S9] 각 헤더의 형식은 Standard Webhooks 스펙을 따른다. [S19]

- `webhook-id`: 이벤트 고유 식별자.
- `webhook-timestamp`: 유닉스 시각(초 단위 정수).
- `webhook-signature`: `v1,{base64 서명}` 형식. 여러 개면 공백으로 구분한다.

스펙의 헤더 예시 [S19]:

```
webhook-id: msg_2KWPBgLlAfxdpx2AI54pPJ85f4W
webhook-timestamp: 1674087231
webhook-signature: v1,K5oZfzN95Z9UVu1EsfQmfVNQhnkZ2pj9o9NDN/H/pI4=
```

### 발신 IP

PortOne V2 웹훅 IP는 `52.78.5.241`이다. IP가 바뀌면 미리 메일로 알린다고 한다. [S1]

## 3. 서명 검증

- PortOne은 Standard Webhooks에 따른 메시지 검증을 지원한다. JVM과 JS용 검증 코드는 서버 SDK로 배포한다. SDK를 쓰지 않으면 Standard Webhooks 스펙대로 직접 구현해도 된다. [S1]
- 웹훅 시크릿은 콘솔 `[결제알림(Webhook) 관리]` 탭에서 모드(테스트/실연동)를 고른 뒤 `[웹훅 시크릿 발급]`으로 받는다. API Secret과는 다른 값이다. [S1][S9]
- JVM SDK: `io.portone:server-sdk`(Maven Central 최신 `0.24.0`, 2026-05-04 갱신 [S20]). 검증 클래스는 `io.portone.sdk.server.webhook.WebhookVerifier`다. [S9]

  ```kotlin
  val verifier = WebhookVerifier("웹훅 시크릿")   // 스레드 간 공유 가능한 불변 객체
  val webhook: Webhook = verifier.verify(
      msgBody = rawBody,                            // 파싱 전 원문 문자열
      msgId = headers["webhook-id"],
      msgSignature = headers["webhook-signature"],
      msgTimestamp = headers["webhook-timestamp"],
  )   // 실패하면 WebhookVerificationException
  ```

- SDK가 하는 일(소스 코드 기준) [S9]:
  1. 세 헤더 중 하나라도 없으면 실패한다.
  2. `webhook-timestamp`가 현재 시각과 **5분 넘게 차이 나면** 실패한다(너무 오래됐거나 너무 미래).
  3. 시크릿 앞의 `whsec_` 접두사를 떼고 Base64로 디코드한 값을 HMAC-SHA256 키로 쓴다.
  4. `"{webhook-id}.{webhook-timestamp}.{본문}"` 문자열에 서명한다.
  5. `webhook-signature`를 공백으로 나눠 `v1` 서명 중 하나라도 상수 시간 비교로 일치하면 통과한다.
  6. 통과하면 본문을 `Webhook` 타입으로 역직렬화해 돌려준다(모르는 필드는 무시).
- 검증에는 **파싱 전 원문 본문**이 필요하다. 공식 예제도 본문을 텍스트로 받는다. [S1]
- 공식 예제는 검증을 통과한 뒤에도 결제 단건 조회 API로 상태와 금액을 다시 확인하고, 조회 결과가 없으면 200으로 응답한다. 검증에 실패하면 400으로 응답한다. [S1]
- 검증 대신 「웹훅 내용은 믿지 않고 결제 조회 API 응답만 믿는」 방식도 문서가 인정하는 전략이다. [S1]

## 4. 재전송 정책

### 확인된 사실

- 네트워크 문제나 고객사 오류로 웹훅이 실패하면 **최대 5회까지 재전송**한다. [S1]
- 대기 시간은 exponential backoff로 **0 → 1 → 4 → 16 → 64 → 256분**이고, 여기에 무작위로 지연을 바꾸는 jitter가 붙는다. [S1]
- 웹훅의 **Connection Timeout과 Read Timeout은 모두 30초**다. [S1]
- PortOne 기술 블로그(2024-02)는 구현을 이렇게 설명한다. [S8]
  - 첫 시도는 대기 없이 보낸다. n번째 시도(n ≥ 2)는 `4^(n-2)`분을 기준으로 기다린다.
  - jitter는 Full Jitter 방식이다. 기준 대기 시간에 0~1 사이 난수를 곱하므로 실제 대기 시간은 0부터 기준값 사이에서 고르게 정해진다.
  - 재시도는 SQS 지연 큐로 예약하고, 최대 횟수를 넘긴 메시지는 DLQ로 보낸다.
  - 해석: 첫 시도 1회와 재시도 5회를 합해 한 이벤트에 최대 6번 보낸다. 문서의 「0 → … → 256분」 6개 값은 각 시도의 대기 시간이다.
- 결제 조회 응답의 웹훅 발송 내역(`webhooks[]`)에는 `status`(`SUCCEEDED`, `FAILED_NOT_OK_RESPONSE`, `FAILED_UNEXPECTED_ERROR`), `currentExecutionCount`(현재 발송 횟수), `maxExecutionCount`(최대 발송 횟수)가 있다. [S5]
- 수동 재발송 API가 있다: `POST /payments/{paymentId}/resend-webhook`, 본문 `{webhookId?}`(비우면 가장 최근 웹훅). 같은 webhook id의 수동 재시도 횟수가 한도에 닿으면 `MaxWebhookRetryCountReachedError`를 돌려준다. [S5]
- PortOne 서버는 웹훅을 보낸 뒤 고객사 응답을 기다리지 않고 브라우저에 302 리다이렉트를 보낸다. 그래서 **웹훅과 브라우저 결제 완료 중 어느 쪽이 먼저 도착할지 보장하지 않는다.** 순서 보장이 필요하면 상점 아이디를 적어 기술지원 메일로 「웹훅 우선순위」를 요청할 수 있다. [S1]

### 확인 못 함

- PortOne 문서는 무엇을 「성공」으로 보는지(2xx 전체인지, 200만인지) 명시하지 않는다. 발송 상태 값 이름(`FAILED_NOT_OK_RESPONSE`)과 Standard Webhooks 스펙(「2xx면 성공, 그 밖의 응답 · 시간 초과 · 연결 끊김은 실패」 [S19])으로 보아 2xx를 성공으로 볼 가능성이 높지만, PortOne이 그렇게 적은 곳은 찾지 못했다.
- 재시도 때 `webhook-id`가 같은 값으로 유지되는지 PortOne 문서는 직접 말하지 않는다. Standard Webhooks 스펙은 「재시도해도 id는 같고, `webhook-timestamp`는 시도마다 새 값」이라고 정한다. [S19] PortOne이 확인해 준 것은 본문 `timestamp`가 재시도에도 그대로라는 점뿐이다. [S1]
- 문서의 「최대 5회」와 `maxExecutionCount` 필드 값이 같은 기준(재시도 수인지 총 발송 수인지)인지는 확인하지 못했다.
- 블로그의 구현 설명은 2024-02 시점의 글이다. 지금도 같은지는 확인할 수 없다. 문서(S1)의 대기 시간 수열은 블로그와 일치한다.

## 5. 결제 단건 조회 API

- `GET https://api.portone.io/payments/{paymentId}` (operationId `getPayment`). `paymentId`는 경로에 URL 인코딩해 넣는다. 쿼리 `storeId`는 선택이며 비우면 인증 정보의 상점을 쓴다. [S4][S5]
- 인증: `Authorization: PortOne {V2 API Secret}`. Bearer 액세스 토큰 방식도 스키마에 있다. [S4][S5][S7]
- 응답은 `status` 값으로 갈리는 결제 건 객체다. `status`는 `READY`, `PAID`, `FAILED`, `CANCELLED`, `PARTIAL_CANCELLED`, `PAY_PENDING`, `VIRTUAL_ACCOUNT_ISSUED` 중 하나다. [S5]
- 확정 단계에서 볼 주요 필드 [S5]:
  - `id`(= paymentId), `transactionId`, `storeId`
  - `status`
  - `amount.total`(총 결제 금액), `amount.paid`(실제 결제 금액), `amount.cancelled`(총 취소 금액), `amount.discount` 등. `total`, `taxFree`, `discount`, `paid`, `cancelled`, `cancelledTaxFree`가 필수다.
  - `currency`, `orderName`, `channel.type`(`TEST`/`LIVE`)
  - `requestedAt`, `statusChangedAt`, `updatedAt`, `paidAt`(PAID일 때 필수), `cancelledAt`(CANCELLED일 때)
  - `cancellations[]`, `webhooks[]`
- 공식 예제는 `amount.total`을 고객사 주문 금액과 비교하고, `status == "PAID"`일 때 완료 처리한다. [S1][S4]
- 오류 응답 본문은 `{type, message}`다. 결제 건이 없으면 404에 `type: "PAYMENT_NOT_FOUND"`, 인증 실패는 401 `UNAUTHORIZED`, 권한 없음은 403 `FORBIDDEN`, 입력 오류는 400 `INVALID_REQUEST`다. [S5]

## 6. 결제 취소 API

- `POST https://api.portone.io/payments/{paymentId}/cancel` (operationId `cancelPayment`), 인증 헤더는 조회와 같다. [S5][S7]
- 요청 본문 [S5][S7]:
  - `reason`(필수): 취소 사유.
  - `amount`(선택): 취소 금액. **비우면 전액 취소**, 넣으면 부분 취소다.
  - `currentCancellableAmount`(선택): 이 요청 전의 취소 가능 잔액. 넣으면 잔액이 맞을 때만 취소한다. 부분 취소 예제가 이 값을 함께 보낸다.
  - `requester`(선택): `Customer` 또는 `Admin`.
  - `skipWebhook`(선택): `true`면 취소가 성공해도 웹훅을 보내지 않는다.
  - 그 밖에 `taxFreeAmount`, `vatAmount`, `refundAccount`(가상계좌 등 계좌 환불), `storeId` 등.
- 성공 응답은 `{ "cancellation": { ... } }`이다. `cancellation.status`는 `SUCCEEDED`, `REQUESTED`, `FAILED` 중 하나이고, `id`(취소 내역 아이디), `totalAmount`, `reason`, `requestedAt`, `cancelledAt` 등을 담는다. [S5]
- 주요 오류: 404 `PAYMENT_NOT_FOUND`, 409 `PAYMENT_NOT_PAID`(결제 완료 전), 409 `PAYMENT_ALREADY_CANCELLED`, 409 `CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN`(잔액 검증 실패), 409 `CANCEL_AMOUNT_EXCEEDS_CANCELLABLE_AMOUNT`, 502 `PG_PROVIDER`(PG 오류). [S5]
- 취소가 끝나면 `Transaction.Cancelled`(전액) 또는 `Transaction.PartialCancelled`(부분) 웹훅이 온다. 비동기로 취소되는 경우에는 먼저 `Transaction.CancelPending`이 온다. [S1]
- 결제와 취소는 반드시 PortOne 콘솔이나 API로만 해야 한다. PG에서 직접 취소하면 상태가 어긋난다. 카드 취소 기한은 통상 1년이다. [S7]

## 7. 결제창 유효 시간

### 확인된 사실

- 브라우저 SDK의 공통 결제 요청 파라미터(`PaymentRequestBase`)는 `storeId`, `paymentId`, `orderName`, `totalAmount`, `currency`, `payMethod`, `channelKey`, `redirectUrl`, `noticeUrls`, `confirmUrl`, `bypass` 등이다. 이 안에 결제창 유효 시간이나 만료 시각을 정하는 필드는 없다. 카드(`card`)와 간편결제(`easyPay`) 하위 파라미터에도 없다. [S11]
- 만료와 관련된 파라미터는 다음뿐이다. [S11][S12]
  - 가상계좌 입금 기한 `virtualAccount.accountExpiry`(`validHours` 또는 `dueDate`), 편의점 결제 기한 `paymentDeadline`.
  - PG별 `bypass`. 예를 들어 토스페이(V2) `bypass.tosspayV2.expiredTime`은 `yyyy-MM-dd HH:mm:ss` 형식의 결제 만료 기한이다. 넣지 않으면 15분, 최대 60분이다. [S12]

### 해석

- 선점 유지 시간(5분)에 맞춰 결제창을 PortOne 쪽에서 닫게 하는 공통 방법은 없다. 만료 뒤 결제는 이 프로젝트가 정한 대로 「확정 단계에서 좌석 상태를 보고 확정하거나 취소」해야 한다.
- 승인 자체를 막고 싶다면 아래 10절의 수동 승인 기능이 공식적으로 제공된다.

### 확인 못 함

- 토스페이 외 다른 PG(카드사 결제창 등)의 자체 세션 만료 시간은 PG마다 다르고, 이 조사에서는 모으지 않았다.

## 8. 테스트 환경에서 웹훅 받기

- V2 문서는 웹훅 수신 주소를 「공개된 URL」로 전제한다. [S1] 콘솔의 `호출 테스트` 버튼으로 저장한 URL에 시험 웹훅을 보낼 수 있다. [S1]
- V1 웹훅 문서는 「웹훅 호출 테스트는 외부망에서 접근 가능한 도메인만 가능하다. `localhost`는 호출할 수 없지만 ngrok으로 `localhost`를 외부 도메인으로 포워딩하면 그 도메인을 callback URL로 쓸 수 있다」고 적고 있다. [S6]
- PortOne 공식 샘플 저장소(`portone-io/portone-sample`)의 안내 문서도 「웹훅을 받으려면 ngrok 같은 도구로 로컬 서버를 노출하고, 그 URL을 콘솔에 넣으라」고 한다. [S21]
- 해석: 로컬 개발에서는 ngrok 같은 터널 URL을 테스트 모드 웹훅 URL로 넣으면 된다. 다만 V2 문서 본문에 터널을 명시적으로 허용하는 문장은 없고, 근거는 V1 문서와 공식 샘플 저장소다.
- 결제 단위로 받을 곳을 바꾸려면 `noticeUrls`를 쓴다. [S1]

### 확인 못 함

- V2 웹훅 URL이 `https`만 허용하는지, 특정 포트를 막는지는 문서에서 찾지 못했다.

## 9. 웹훅 전달 지연 시간

- **PortOne 공식 문서, 스키마, 기술 블로그 어디에도 결제 승인부터 웹훅 도착까지 걸리는 시간에 대한 수치(평균, p95, SLA 등)는 없다.** 문서에 있는 시간 값은 시간 제한 30초와 재전송 대기 시간뿐이다. [S1][S8]
- 직접 재는 데 쓸 수 있는 값(스키마에 존재하는 필드) [S5]:
  - 결제: `paidAt`(결제 완료 시점), `statusChangedAt`.
  - 웹훅 발송 내역 `webhooks[]`: `triggeredAt`(웹훅 처리 시작 시점), `request.requestedAt`(요청 시점), `response.respondedAt`(응답 시점), `response.code`(우리 서버가 돌려준 HTTP 코드), `status`, `currentExecutionCount`.
  - 본문 `timestamp`(이벤트 발생 시각, 재시도에도 고정) [S1]와 헤더 `webhook-timestamp`(Standard Webhooks상 시도 시각) [S19].
- 해석: `paidAt` → `webhooks[].request.requestedAt` 차이로 PortOne 내부 발송 지연을, 우리 서버의 수신 시각과 `webhook-timestamp` 차이로 네트워크 구간을, `request.requestedAt` → `response.respondedAt`로 PortOne이 본 우리 응답 시간을 잴 수 있다. 각 필드의 정확한 기준 시점은 스키마 제목 이상으로 문서화돼 있지 않으므로 실측할 때 함께 검증해야 한다.

## 10. 설계에 영향을 주는 추가 사실

- **결제 정보 사전 등록 API**가 있다: `POST /payments/{paymentId}/pre-register`, 본문 `{totalAmount?, taxFreeAmount?, currency?, storeId?}`. 이미 결제된 건이면 409 `ALREADY_PAID`를 돌려준다. [S5] 이 API를 쓴 뒤 결제창에서 금액이 다르면 어떻게 되는지, 사전 등록만 한 건이 단건 조회에서 어떤 상태로 보이는지는 V2 문서에서 설명을 찾지 못했다(확인 못 함).
- **수동 승인**: 채널 설정에서 켜면 결제창이 끝나도 자동 승인되지 않는다. `requestPayment` 응답에 `paymentToken`이 오고, 서버가 `POST /payments/{paymentId}/confirm`(본문 `paymentToken` 필수, `totalAmount` · `currency` · `isTest` 등 검증용 값 권장)을 불러야 승인된다. 문서는 「수량이 한정된 선착순 판매」를 용도로 든다. 승인 API는 가능한 한 바로 불러야 하며, 오래 기다리면 PG별 시간 제한에 걸려 실패할 수 있다. 엑심베이, 페이레터 해외결제, 이지페이(KICC)는 지원하지 않는다. [S5][S22]
- **컨펌 프로세스**: 승인 직전에 `confirmUrl`로 `Transaction.Confirm` 웹훅을 보내고 고객사가 실패로 응답하면 승인하지 않는 기능이다. 문서는 선착순 판매를 대표 사례로 든다. 기술지원 메일로 신청해야 하고, V2 신규 연동에는 수동 승인을 권장한다. [S18]
- 해석: 현재 설계(선점 만료 뒤 결제가 들어오면 좌석이 비었을 때 확정, 아니면 결제 취소)는 승인 뒤에 정리하는 방식이다. 수동 승인을 쓰면 승인 전에 좌석을 확인해 취소 자체를 줄일 수 있다. 어느 쪽을 쓸지는 지도(#2)에서 정할 일이며 이 문서는 사실만 적는다.
- 같은 `paymentId`로 여러 번 결제를 시도할 수 있지만 최종 성공은 한 번뿐이다(중복 결제 방지). 시도마다 `transactionId`가 달라질 수 있다. [S1][S4]

## 모의 PG가 흉내 낼 모양 (위 사실의 정리)

- 웹훅 요청: `POST`, `Content-Type: application/json`, 헤더 `webhook-id` · `webhook-timestamp`(초) · `webhook-signature`(`v1,` + Base64(HMAC-SHA256(key, "{id}.{timestamp}.{body}"))), 본문 `{type, timestamp, data:{storeId, paymentId, transactionId[, cancellationId]}}`. [S1][S9][S19]
- 재전송: 실패 시 0 → 1 → 4 → 16 → 64 → 256분 기준에 Full Jitter, 최대 5회 재전송, 시간 제한 30초. [S1][S8]
- 조회: `GET /payments/{paymentId}`, `Authorization: PortOne {secret}`, 응답 `{status, id, transactionId, storeId, amount:{total, paid, cancelled, ...}, currency, channel:{type}, paidAt, ...}`, 없으면 404 `{type:"PAYMENT_NOT_FOUND"}`. [S5]
- 취소: `POST /payments/{paymentId}/cancel`, 본문 `{reason, amount?, currentCancellableAmount?}`, 응답 `{cancellation:{status, id, totalAmount, ...}}`, 이미 취소면 409 `PAYMENT_ALREADY_CANCELLED`. [S5]
- 순서: 웹훅과 브라우저 완료 요청의 도착 순서는 보장되지 않는다. [S1]

## 출처

- [S1] 웹훅 연동하기 (V2): https://developers.portone.io/opi/ko/integration/webhook/readme-v2?v=v2
- [S2] 연동 정보 (콘솔 가이드: 채널 관리, 식별코드·API Keys, 웹훅 관리): https://developers.portone.io/opi/ko/console/guide/channel-manage?v=v2
- [S3] 연동 준비하기 (결제대행사 채널 설정): https://developers.portone.io/opi/ko/integration/ready/readme?v=v2
- [S4] 인증 결제 연동하기 (V2): https://developers.portone.io/opi/ko/integration/start/v2/checkout?v=v2
- [S5] V2 OpenAPI 스키마 (버전 1.16.0, 2026-11-08 내려받음): https://developers.portone.io/schema/v2.openapi.json — `getPayment`, `cancelPayment`, `preRegisterPayment`, `resendWebhook`, `confirmPayment`, 스키마 `Payment`, `PaymentAmount`, `CancelPaymentBody`, `PaymentCancellation`, `PaymentWebhook`, `PaymentWebhookStatus`, `SelectedChannelType`, `ConfirmPaymentBody`
- [S6] 웹훅 연동하기 (V1, 「localhost로 호출테스트 하기」): https://developers.portone.io/opi/ko/integration/webhook/readme-v1?v=v1
- [S7] 결제취소(환불) 연동하기 (V2): https://developers.portone.io/opi/ko/integration/cancel/v2/readme?v=v2
- [S8] PortOne 기술 블로그, 「포트원 V2 이야기 - 웹훅 재시도, 어디까지 고도화해 보셨나요?」(2024-02-28): https://developers.portone.io/blog/posts/2024-02/v2-webhook
- [S9] PortOne 서버 SDK (JVM) `WebhookVerifier.kt`: https://github.com/portone-io/server-sdk/blob/main/jvm/lib/src/main/kotlin/io/portone/sdk/server/webhook/WebhookVerifier.kt
- [S10] PortOne 서버 SDK 웹훅 타입 정의 `codegen/common/webhook/transaction.ts`: https://github.com/portone-io/server-sdk/blob/main/codegen/common/webhook/transaction.ts
- [S11] V2 브라우저 SDK 스키마 (`PaymentRequestBase`, `PaymentRequestUnion*`): https://developers.portone.io/schema/browser-sdk.yml
- [S12] 토스페이 (V2) 연동 가이드 (`bypass.tosspayV2.expiredTime`): https://developers.portone.io/opi/ko/integration/pg/v2/tosspay-v2?v=v2
- [S13] 콘솔 가이드 (가입 · 개발 연동 테스트): https://developers.portone.io/opi/ko/console/guide/reg
- [S14] 토스페이먼츠 (V2) 연동 가이드: https://developers.portone.io/opi/ko/integration/pg/v2/tosspayments?v=v2
- [S15] 결제 내역 조회 (콘솔 가이드, 테스트 결제 필터): https://developers.portone.io/opi/ko/console/guide/payments
- [S16] 스마트로 (V2) 연동 가이드 (테스트연동 FAQ): https://developers.portone.io/opi/ko/integration/pg/v2/smartro-v2?v=v2
- [S17] 카카오페이 (V2) 연동 가이드: https://developers.portone.io/opi/ko/integration/pg/v2/kakaopay?v=v2
- [S18] 컨펌 프로세스 (V2): https://developers.portone.io/opi/ko/extra/confirm-process/readme-v2?v=v2
- [S19] Standard Webhooks 스펙: https://github.com/standard-webhooks/standard-webhooks/blob/main/spec/standard-webhooks.md
- [S20] Maven Central `io.portone:server-sdk` 메타데이터: https://repo1.maven.org/maven2/io/portone/server-sdk/maven-metadata.xml
- [S21] PortOne 공식 샘플 저장소 안내 문서: https://github.com/portone-io/portone-sample/blob/main/CLAUDE.md
- [S22] 수동 승인 (V2): https://developers.portone.io/opi/ko/extra/manual-confirm/readme-v2?v=v2
