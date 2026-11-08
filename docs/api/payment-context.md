# V6 결제 컨텍스트 앱 전달

`POST /api/v1/screenings/{sid}/holds` 응답에 `customData`가 추가됐다. 앱은 이 객체를 수정하지 않고 PortOne V2 `requestPayment`의 같은 이름 필드에 전달한다. 서버가 좌석·토큰·금액을 암호화해 담았으며 앱이 내용을 해석할 필요는 없다.

PortOne의 [V2 결제 요청 형식](https://developers.portone.io/sdk/ko/v2-sdk/payment-request?v=v2)은 `customData`를 허용한다.

```json
{
  "holdId": "예시-선점-ID",
  "paymentId": "예시-결제-ID",
  "amount": 30000,
  "heldUntil": "2026-11-08T09:30:00Z",
  "customData": {
    "ticketingContext": "서버가 발급한 불투명 문자열"
  }
}
```

```javascript
const hold = await createHold({ seats: ["A-12", "A-13"] });
await PortOne.requestPayment({
  paymentId: hold.paymentId,
  totalAmount: hold.amount,
  customData: hold.customData,
  // 나머지 결제창 필드는 기존 설정을 사용한다.
});
```

결제창이 닫히거나 앱이 종료돼도 웹훅과 서버 대사가 결제 상태를 확인한다. Redis 재시작으로 선점 기록 일부가 사라졌을 때 서버는 PG 결제 내역의 `customData`로 좌석을 식별한다. 좌석이 비어 있으면 확정하고 다른 사용자가 먼저 잡았다면 결제를 취소한다. 앱은 `GET /api/v1/screenings/{sid}/reservations/me`로 최종 상태를 다시 조회한다.
