# 좌석 예매 지도 #2: V6 이후 핸드오프

## 지금 상태

- 지도: https://github.com/rlagnlfo1004/movie-ticketing-system/issues/2
- 측정 런북 티켓: https://github.com/rlagnlfo1004/movie-ticketing-system/issues/20 — 해결 댓글을 남기고 닫았으며, 지도 `Decisions so far`에 반영했다.
- 브랜치: `feat/seat-redesign`. V6 로컬 증거 커밋은 `21d972b08697ac25e9806b2b037a783238446d20`이며 원격에 푸시했다. 최신 HEAD는 다음 세션에서 `git rev-parse HEAD`로 확인한다. 작업 트리에는 이전부터 있던 미추적 `.claude/`만 남았으며 건드리지 않았다.
- 단계 태그 `v1-db`, `v2-db-fixed`, `v3-pubsub`, `v4-streams`, `v5-lua-active`, `v6-aof-reconcile`는 이미 푸시돼 있다. 새 `run-stage.sh v1 --smoke`부터 `v6 --smoke`까지 모두 로컬에서 종료 코드 0으로 끝났다. `db-baseline.sh --smoke`도 통과했다.
- 로컬 실행 원본은 무시되는 `load-test/results/`에 있고, 상태 스냅샷은 `docs/evidence/`에 커밋했다. 로컬 수치를 정식 성능 수치로 쓰지 않는다. 마지막 V6 짧은 실행에서는 AOF `everysec`과 `always`를 모두 적용했고, Redis 재시작 뒤 승인 10건 = 확정 10건 + 취소 0건, 좌석 중복 0건을 확인했다.
- **AWS Terraform apply는 실행하지 않았다. 유료 리소스를 만들지 않았다.** Terraform은 이전 세션의 지정 계정 plan(22 add, 0 change, 0 destroy)까지만 통과했다. 원격 배포 스크립트는 가상 `hosts.env`로 일곱 호스트 Compose 생성·구문·사설 주소를 검증했지만 실제 EC2에서는 아직 실행하지 않았다.
- 사용자의 이번 지시: V6까지만 진행하고 나머지는 다음 세션에 핸드오프한다. 이 문서 이후 새 티켓을 claim하거나 AWS apply를 시작하지 않는다.

## 다른 컴퓨터에서 시작할 때

이 문서의 저장소 사본을 원본으로 삼는다. 임시 폴더의 이전 핸드오프 경로는 이 컴퓨터에서만 열린다.

1. 저장소를 clone하거나 최신 `feat/seat-redesign` 브랜치를 fetch하고 위 여섯 태그도 fetch한다. `docs/evidence/`의 로컬 스냅샷은 Git에 있지만 `load-test/results/`의 짧은 실행 원본은 `.gitignore` 대상이므로 다른 컴퓨터에 없다.
2. AWS 측정 전에 새 컴퓨터에 `movie-loadtest` 프로필과 Terraform이 참조하는 SSH 개인·공개 키를 준비한다. `.env`, `.local/`, AWS 자격 증명, PortOne 값은 Git에 없고 이 문서에도 넣지 않았다. 비밀 값은 저장소에 추가하지 않는다.
3. Terraform `apply`를 실행한 컴퓨터의 로컬 state를 같은 날 `destroy` 때까지 보존한다. 다른 컴퓨터로 실행 주체를 바꿔야 한다면 state를 안전하게 이전하고 일치 여부를 확인하기 전에는 새 `apply`를 하지 않는다.

## 다음 세션의 순서

1. `docs/agents/seat-redesign-kickoff.md`와 지도 #2를 읽는다. **각 티켓을 시작하기 직전** `gh issue view <번호> --repo rlagnlfo1004/movie-ticketing-system --json title,body,comments`로 본문·댓글을 다시 확인한 뒤 claim한다.
2. [GitHub Actions CI 추가](https://github.com/rlagnlfo1004/movie-ticketing-system/issues/24)는 열려 있고 blocker가 없다. 이것이 [유료 측정일 실행](https://github.com/rlagnlfo1004/movie-ticketing-system/issues/21)의 **유일한 열린 blocker**다.
3. [데모 페이지와 화면 캡처](https://github.com/rlagnlfo1004/movie-ticketing-system/issues/23)도 열려 있고 blocker가 없다. 유료 측정과 독립적으로 진행할 수 있지만, [PortOne 실제 채널 사람 작업](https://github.com/rlagnlfo1004/movie-ticketing-system/issues/22)과 [Figma 결과 반영](https://github.com/rlagnlfo1004/movie-ticketing-system/issues/26)을 막고 있다.
4. 측정 뒤 [V7 진행 여부 결정](https://github.com/rlagnlfo1004/movie-ticketing-system/issues/14)은 사람의 판단을 기다린다. PortOne 실제 채널 작업 #22도 사람 작업이므로 Codex가 claim하지 않는다. #26은 #21과 #23이 모두 끝난 뒤 진행한다.

## AWS 유료 측정일 인계

원본 절차는 [측정일 티켓 #21](https://github.com/rlagnlfo1004/movie-ticketing-system/issues/21), `infra/aws/README.md`, `load-test/README.md`에 있다. 새 세션은 #24가 닫히고 하루 안에 **apply → 측정 원본 커밋·푸시 → destroy → 잔존 0 → 액세스 키 삭제**를 끝낼 충분한 시간이 있을 때만 #21을 시작한다.

- AWS 계정 `976071095824`, 리전 `ap-northeast-2`, 프로필 `movie-loadtest`만 쓴다. `cmb`는 다른 조직 계정이므로 쓰지 않는다. 시작 전에 `aws sts get-caller-identity --profile movie-loadtest --region ap-northeast-2`를 다시 확인하고 `./infra/aws/plan.sh`로 최신 작업자 IP의 `/32`와 예상 자원을 확인한다. Terraform 로컬 state 파일을 destroy 전까지 보존한다. `apply`와 `destroy`에는 같은 `TF_VAR_operator_cidr`(당일 작업자 IPv4 `/32`)를 전달한다.
- #21의 시작 조건, 멈춤 조건, 같은 날 정리 규칙을 확인한다. `apply` 뒤에는 user-data의 Docker·node_exporter·시간 동기화, MySQL EBS 마운트를 실제 호스트에서 검증한다. **이 원격 기동 경로는 아직 실배포 검증 전이다.** 예상 밖 NAT Gateway·로드 밸런서·볼륨이나 계정 불일치가 나오면 시작 문서의 멈춤 조건을 따른다.
- `./infra/aws/render-hosts-env.sh`로 사설·공인 IP 파일을 만든다. 앱 시작 전 `./load-test/db-baseline.sh --hosts-env infra/aws/hosts.env --ssh-key ~/.ssh/id_ed25519`를 실행한다. `./load-test/run-stage.sh v1 --smoke --hosts-env infra/aws/hosts.env --ssh-key ~/.ssh/id_ed25519`로 인프라 짧은 스모크를 확인한 뒤 `./load-test/run-stage.sh v1`부터 `v6`까지 같은 `--hosts-env`와 `--ssh-key` 옵션으로 정식 측정을 진행하고 `./load-test/report.sh --all`을 실행한다. 명령 세부는 AWS README에 있다.
- `load-test/results/`는 `.gitignore` 대상이며 **이번 세션의 로컬 스모크와 중단한 시도도 들어 있다.** 정식 측정에서 생성한 각 배치 디렉터리와 최신 `load-test/results/README.md`만 골라 `git add -f`한다. PNG·Prometheus JSON·k6 summary·JFR·상태 스냅샷·README 링크·비밀 값·좌석 중복을 확인하고 커밋·푸시한 다음 destroy한다. JFR은 환경 변수 시크릿을 포함할 수 있으므로 런북이 `jdk.VirtualThreadPinned` 이벤트만 남겨 정제한다.
- destroy 직후 `./infra/aws/destroy-check.sh <결과.json>`으로 잔존 유료 리소스 0개를 확인한다. 측정용 IAM 사용자 `movie-loadtest`의 액세스 키 ID를 조회해 해당 키를 삭제하고 `python3 infra/stages/measurement-secrets.py delete`로 측정용 Grafana 비밀 파일을 지운다. 결과와 정리 근거를 #21 댓글에 기록한다. destroy 실패 등은 시작 문서의 멈춤 조건을 따른다. **이번 세션에는 apply·destroy·액세스 키 삭제를 실행하지 않았다.**

## 권장 스킬

- `mattpocock-skills:wayfinder`: frontier, claim, 해결 댓글, 지도 갱신.
- `mattpocock-skills:tdd`: #23·#24의 공개 경계 구현과 검증.
- `mattpocock-skills:domain-modeling`: 새 용어 또는 결정이 생길 때만 GLOSSARY/ADR 반영.
- #26에서는 Figma 쓰기 전에 `figma:figma-use`와 해당 화면 작업 스킬을 읽는다.
