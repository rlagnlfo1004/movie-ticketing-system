# V5 문제 → 해결

V4의 좌석 선점은 Redis Lua로 처리했지만, 시작 전에 waiting-api HTTP 호출로 입장 여부를 확인했다. waiting-api가 모두 중단되면 Redis의 active 키가 살아 있어도 선점 요청이 실패했다. V5는 같은 선점 Lua 안에서 active 키를 확인해 이 런타임 의존을 없앴다.

`waiting-down-before.json`과 `waiting-down-after.json`은 waiting-api 세 대를 멈춘 동안의 active 키와 좌석 선점 키 수를 보존한다. 이 사이 이미 active인 토큰의 선점 API가 201을 반환했고 해당 좌석의 Redis hold 키가 생성됐다. active 키가 없는 토큰은 `NOT_ADMITTED`(403)를 받았다. 다른 토큰의 TTL 만료 때문에 active 키 총수는 실행 사이에 변할 수 있어, 성공 여부는 해당 토큰과 좌석 키로 판단한다.

짧은 k6 전체 흐름과 Grafana 캡처도 확인했다. 처리량·성공률의 단계 간 비교는 공통 AWS 측정일에 한다.
