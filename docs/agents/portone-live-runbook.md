# PortOne 테스트 채널 검증 (#22)

콘솔 설정과 결제창 조작은 사람이 한다. 실제 채널은 로컬 Docker Compose와 ngrok만 사용하고 AWS 인프라는 사용하지 않는다.

## 준비

1. `~/.config/movie-ticketing/portone.env`(권한 600)에 `PORTONE_STORE_ID`, `PORTONE_CHANNEL_KEY`, `PORTONE_API_SECRET`, `PORTONE_WEBHOOK_SECRET`을 둔다. 비밀 값은 Git·티켓·스크린샷에 넣지 않는다.
2. 이 기기의 ngrok 인증을 설정한다. 토큰은 대화에 보내지 않는다.
3. 서버 JAR를 빌드하고 실제 PortOne API로 reservation-api를 시작한다. `docker-compose.portone.yml`은 URL과 환경 변수 **이름만** 저장하며 비밀 값은 로컬 env 파일에서 읽는다.

```bash
./gradlew :waiting-api:bootJar :reservation-api:bootJar :reservation-worker:bootJar
docker compose --env-file .env --env-file "$HOME/.config/movie-ticketing/portone.env" \
  -f docker-compose.yml -f docker-compose.v7.yml -f docker-compose.portone.yml up -d \
  redis redis-replica-1 redis-replica-2 sentinel-1 sentinel-2 sentinel-3 \
  mysql mock-pg reservation-worker reservation-api waiting-api-1 waiting-api-2 waiting-api-3 nginx
ngrok http 8000
```

ngrok이 표시한 HTTPS 주소에 `/api/v1/webhooks/portone`을 붙여 PortOne 콘솔의 **테스트 모드 V2** 웹훅 URL로 등록한다. 버전은 `2024-04-25`로 둔다. 기존 메일상자 URL은 사용자가 교체를 허락했다(#22 댓글). 웹훅 시크릿은 로컬 파일과 같은 값을 쓴다.

## 확인 순서

`python3 load-test/demo-capture/open-live.py --base-url 'https://<ngrok-host>/demo/'`로 실제 화면을 연다. 첫 결제 전에 테스트 채널 여부와 결제창에 실제 청구 안내가 있는지 사람이 확인한다.

1. 정상 결제: PortOne 결제 뒤 웹훅만으로 예약 `CONFIRMED`, Redis·MySQL 좌석 일치.
2. 브라우저 종료: 결제 직후 완료 API 호출 없이 웹훅으로 확정.
3. 만료 뒤 결제: 다른 토큰이 같은 좌석을 잡았다면 원 결제를 취소하고 새 선점을 유지.
4. 웹훅 재발송: 동일 결제 ID의 예약과 좌석은 한 번만 반영.

PortOne의 `GET /payments/{paymentId}` 응답 중 웹훅 요청·응답 시각만 기록해 실측 지연을 계산한다. 비밀 헤더·결제수단 정보·개인정보는 원본에 남기지 않는다. 첫 결제 결과에 따라 비용이 실제 청구되면 다음 결제 시나리오로 넘어가기 전에 중단한다.

공유 테스트 상점에는 다른 프로젝트 결제가 있을 수 있다. 대사는 이 프로젝트 DB 결제 ID 또는 복호화 가능한 `ticketingContext`가 있는 결제만 처리한다. 관련 코드: `reservation-api/.../ReconciliationService.java`.
