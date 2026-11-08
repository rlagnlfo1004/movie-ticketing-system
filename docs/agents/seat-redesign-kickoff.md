# 좌석 예매 SSOT 재구성: Codex 시작 문서

이 문서 하나로 지도 #2의 남은 티켓을 처음부터 끝까지 진행한다. 목적지는 지도 본문의 `Destination`이고, 결정 · 규칙 · 용어의 원본은 지도와 각 티켓이다. 이 문서는 진행 순서와 운영 규칙만 담는다.

- 지도: https://github.com/rlagnlfo1004/movie-ticketing-system/issues/2 (`gh issue view 2`)
- 용어: `GLOSSARY.md` · 결정 기록: `docs/adr/0001`, `docs/adr/0002`
- 작업 브랜치: `feat/seat-redesign` (이 문서가 들어 있는 브랜치). 모든 작업을 여기에 커밋하고, 끝나면 `main`으로 PR을 연다.

## 스킬

- 티켓 진행: `$wayfinder` 의 **Work through the map** 절차(로드 → 선택 → claim → 해결 → 기록)를 따른다. 단, 이 지도는 Notes에 적힌 대로 **구현까지 포함**하고, 한 세션에서 frontier가 빌 때까지 티켓을 연속으로 진행한다.
- 구현 티켓(`wayfinder:task` 중 V1~V6, 하네스 · 캡처 · 런북): `$tdd` (red → green → refactor).
- 용어나 결정이 새로 생기면: `$domain-modeling` 으로 `GLOSSARY.md` · ADR에 바로 반영한다.
- 티켓 본문만으로 정할 수 없는 선택이 나오면: 그 티켓에 질문과 권장안을 댓글로 남기고, 권장안으로 진행한다. 되돌리기 어려운 선택만 멈춤 조건(아래)으로 처리한다.

## 진행 순서

frontier(열려 있고, 막는 티켓이 모두 닫혔고, 담당자가 없는 티켓)를 지도 순서대로 하나씩 처리한다. 의존 관계는 GitHub dependency에 있으므로 `gh api repos/rlagnlfo1004/movie-ticketing-system/issues/<n>` 의 `issue_dependencies_summary.blocked_by`로 확인한다.

1. **준비 티켓**: #6 k6 시나리오 · 모의 PG, #18 관측 스택 · Grafana 자동 캡처, #19 튜닝 기준선 파일.
   완료 기준: 각 티켓의 "완료 조건"이 로컬 docker compose에서 재현된다.
2. **단계 구현**: #7 V1 → #8 V2 → #10 V3 → #11 V4 → #12 V5 → #13 V6.
   완료 기준: 단계마다 테스트 green, 로컬 스모크(기능 경로 + 장애 주입 경로) 통과, git tag(`v1-db` … `v6-aof-reconcile`) push. 스모크 수치는 결과로 쓰지 않는다.
3. **인프라 · 런북**: #17 Terraform(plan까지), #20 런북과 테스트 방법 문서 생성기.
   완료 기준: `terraform plan` 성공, 로컬에서 `run-stage.sh v1`이 짧은 설정으로 끝까지 돌고 결과 폴더와 README가 생긴다.
4. **#21 유료 측정일**: 티켓 본문의 순서를 그대로 따른다. apply부터 destroy까지 같은 날 끝낸다.
   완료 기준: `load-test/results/`가 push되어 있고, destroy-check 결과(남은 리소스 0)와 액세스 키 삭제가 #21 댓글에 있다.
5. **#14 V7 결정**: 측정 결과를 근거로 권장안을 정리해 댓글로 남기고, 사람의 결정을 기다린다(이 티켓은 HITL).

사람 작업 티켓 #16(AWS 계정 준비)과 #22(PortOne 실결제 검증)는 Codex가 claim하지 않는다. #16이 닫혀 있지 않으면 4단계를 시작하지 않는다. #22는 #21과 독립이다.

## 티켓을 닫을 때마다

1. 티켓에 해결 댓글: 무엇을 했는지, 산출물 경로, 커밋 · tag, 남은 문제.
2. 티켓 close.
3. 지도 본문 `Decisions so far`에 한 줄 추가: `- [티켓 제목](링크): 한 줄 요약`.
4. 해결하면서 새로 선명해진 질문은 새 티켓(지도의 sub-issue, `wayfinder:<type>` 라벨, 의존 관계 연결)으로 만든다.

## 운영 규칙

### 기록
- 모든 단계와 튜닝 변경은 `문제 → 해결` 흐름으로 남긴다.
- 숫자는 측정해서 원본(JSON · 로그)이 저장소에 있는 것만 쓴다. 가정값(모의 PG 지연 등)은 가정이라고 적는다.
- 측정은 한 번뿐이다. Grafana 대시보드 · 패널 PNG, Prometheus 원본 JSON, InnoDB · Redis 상태, k6 summary, 테스트 방법 README를 destroy 전에 모두 커밋 · push한다.

### 커밋
- 논리 단위(엔티티 · 스키마, 서비스, API, worker, 테스트, 인프라, 문서)마다 따로 커밋한다. 각 커밋은 빌드가 깨지지 않는 순서로 쌓는다.
- 메시지는 이 저장소 관례대로 한국어 한 줄 요약이다 (예: `V1 좌석 선점 트랜잭션 구현`).

### AWS
- 계정 `976071095824`, 리전 `ap-northeast-2`, 프로필 `movie-loadtest`만 쓴다. Terraform provider에 `allowed_account_ids = ["976071095824"]`를 둔다.
- 로컬의 `cmb` 프로필은 다른 조직 계정이다. 어떤 명령에도 쓰지 않는다.
- 유료 리소스는 #21에서만 만들고, 같은 날 `terraform destroy` → `infra/aws/destroy-check.sh`(남은 리소스 0) → 측정용 액세스 키 삭제(`aws iam delete-access-key --user-name movie-loadtest`)로 끝낸다.

### 비밀 값
- PortOne 값은 `~/.config/movie-ticketing/portone.env`(권한 600)에서 환경 변수로 읽는다. AWS 자격 증명은 `~/.aws/`의 `movie-loadtest` 프로필에 있다.
- 비밀 값은 저장소 · 티켓 · 로그 · 캡처에 남기지 않는다. 캡처 PNG와 원본 JSON에 시크릿이 찍히지 않았는지 커밋 전에 확인한다.

## 멈춤 조건

아래 중 하나가 생기면 진행을 멈추고, 지도 #2에 상황 · 시도한 것 · 권장안을 댓글로 남긴 뒤 사람의 답을 기다린다.

- `sts get-caller-identity`의 계정이 `976071095824`가 아니다.
- `terraform plan`에 예상하지 않은 유료 리소스(NAT Gateway, 로드 밸런서, 큰 볼륨 등)가 있거나, vCPU 한도(32) · 인스턴스 용량 부족으로 apply가 실패한다.
- 유료 리소스가 떠 있는 상태로 destroy가 실패한다. 이 경우 콘솔에서 지울 리소스 목록을 함께 남긴다.
- 같은 테스트가 원인을 모른 채 세 번 연속 실패한다.
