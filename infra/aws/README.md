# 서울 리전 유료 측정 인프라

이 디렉터리는 지도 「좌석 예매 SSOT 재구성」의 **계획과 측정일 배포 코드**다. 티켓 #17에서는 `terraform plan`까지만 검증한다. `apply`와 `destroy`는 측정일 티켓 #21에서 같은 날 실행한다.

## 구성과 네트워크

모든 호스트는 서울 리전의 한 AZ·한 공개 서브넷에 둔다. 공인 IP는 SSH와 Grafana 운영 접속에만 쓰고, k6→Nginx→앱→Redis/MySQL/모의 PG와 Prometheus 수집은 `hosts.env`의 **사설 IP**를 사용한다. NAT Gateway, 로드 밸런서, Elastic IP는 만들지 않는다. 보안 그룹은 작업자 단일 `/32`에서 SSH 22를 모든 호스트에, Grafana 3000을 `mon`에만 허용한다. 같은 보안 그룹의 호스트끼리만 내부 통신을 허용한다.

| 호스트 | 인스턴스 | vCPU | 서울 온디맨드 시간당 USD |
| --- | --- | ---: | ---: |
| lg | c7g.2xlarge | 8 | 0.3264 |
| edge | c7g.large | 2 | 0.0816 |
| app-waiting | m7g.xlarge | 4 | 0.2006 |
| app-reservation | m7g.xlarge | 4 | 0.2006 |
| redis | r7g.large | 2 | 0.1292 |
| mysql | r7g.xlarge | 4 | 0.2584 |
| mon | t4g.medium | 2 | 0.0416 |
| **합계** | **7대** | **26** | **1.2384** |

루트 gp3 20GiB×7대와 MySQL 별도 gp3 100GiB의 합계 240GiB에, 서울 리전 gp3 $0.0912/GiB-월과 월 730시간 가정을 적용하면 **약 $0.029984/시간**이다. gp3의 기본 3,000 IOPS·125MiB/s는 추가 요금이 없는 범위다. 공인 IPv4 7개는 $0.005×7 = **$0.035/시간**이다. 합계는 **약 $1.303384/시간**이다. 데이터 전송·기타 서비스 요금은 이 값에 포함하지 않았다. `mon`의 T4g CPU 크레딧은 `standard`로 둬 추가 무제한 크레딧 요금을 피한다.

가격은 2026-11-08에 AWS Price List `GetProducts` API로 조회한 [원본 JSON](pricing-2026-11-08.json), 2026-11-09에 조회한 [부하 발생기 확장 가격](pricing-lg-resize-2026-11-09.json)과 [계산 스크립트](cost.py)를 따른다. gp3 기본 성능은 [Amazon EBS 문서](https://docs.aws.amazon.com/ebs/latest/userguide/general-purpose.html), 공인 IPv4 요금은 [Amazon VPC 가격](https://aws.amazon.com/vpc/pricing/)에 근거한다.

## 사용자 데이터와 기동

Terraform은 AWS 계정 `976071095824`·프로필 `movie-loadtest`·리전 `ap-northeast-2`를 고정하고 `allowed_account_ids`로 다른 계정 실행을 막는다. Amazon Linux 2023 arm64 AMI는 AWS 공개 SSM 파라미터로 읽는다. user-data는 Docker·Docker Compose, 각 호스트의 node_exporter, sysctl·nofile, Redis THP 비활성화를 설치한다. MySQL 데이터는 별도 gp3 볼륨을 식별해 `/mnt/movie-mysql`에 마운트한다. 설치가 끝난 뒤 런북이 앱·관측 서비스를 배포한다.

```bash
./infra/aws/plan.sh                 # 계정 확인 → init → validate → plan, apply 없음
python3 infra/aws/cost.py           # 저장한 가격 원본으로 시간당 비용 재계산
```

처음 검증한 계획은 **22개 생성, 변경 0개, 삭제 0개**였다. 정식 V1 첫 실행에서 k6가 3만 VU를 제때 확보하지 못해 부하 발생기를 `c7g.2xlarge`로 교체했다. 교체 plan은 1개 생성·1개 삭제였다. `plan.sh`는 실행 당시 작업자의 공인 IPv4를 `/32`로 읽는다. IP가 바뀌었으면 새 계획에서 보안 그룹 범위를 다시 확인한다. Terraform은 SSH 공개 키만 읽고 개인 키를 상태 파일에 넣지 않는다.

측정일 `apply` 뒤 `render-hosts-env.sh`가 Terraform 출력에서 `hosts.env`를 만든다. #25의 `build-host-env.py`가 이 파일과 단계 env를 합쳐 서버별 환경 파일을 생성한다. `destroy` 뒤 `destroy-check.sh <결과.json>`은 태그가 붙은 리소스, EC2 인스턴스, EBS 볼륨, Elastic IP, NAT Gateway의 잔존 여부를 읽기 전용으로 확인하고 결과를 JSON에 남긴다.

## 측정일 런북

`apply`는 유료 측정 티켓에서만 실행한다. 호스트가 준비되면 저장소 루트에서 다음 순서로 실행한다. 런북 명령은 Terraform `apply`를 호출하지 않는다.

```bash
./infra/aws/render-hosts-env.sh
./load-test/db-baseline.sh --hosts-env infra/aws/hosts.env --ssh-key ~/.ssh/id_ed25519
for stage in v1 v2 v3 v4 v5 v6; do
  ./load-test/run-stage.sh "$stage" --hosts-env infra/aws/hosts.env --ssh-key ~/.ssh/id_ed25519
done
./load-test/report.sh --all
```

`db-baseline.sh`는 앱을 시작하기 전에 MySQL 호스트의 별도 EBS 볼륨에서 fio와 단일 행 UPDATE를 실행한다. `run-stage.sh`는 태그별 JAR·모의 PG·Compose를 일곱 호스트에 배포하고, 실행마다 데이터를 초기화해 원본·스냅샷을 로컬로 수집한다. V2·V4에서는 가상 스레드 끔 비교와 비밀 값이 제거된 JFR을 저장한다. 세부 측정 방법과 파일 검증 기준은 [부하 테스트 런북](../../load-test/README.md)에 있다. 측정 결과를 커밋·푸시한 뒤에만 `destroy`와 잔존 검사·액세스 키 삭제를 진행한다.

## V7 재측정

V1~V6 인프라는 이미 destroy했다. V7은 `enable_redis_ha=true`로 Redis replica 두 호스트를 추가한 **9대·30 vCPU** 구성이다. Sentinel은 edge, app-waiting, app-reservation 호스트에서 1개씩 실행한다. replica는 각각 별도 `r7g.large` 호스트다. 저장된 2026-11-08/09 가격 원본으로 재계산한 추정치는 **$1.576781/시간**이다(`python3 infra/aws/cost.py --v7`). 실제 청구액은 실행 시간과 AWS 과금에 따른다.

```bash
./infra/aws/plan.sh --v7
# 계정·9대·30 vCPU·추가 리소스·SSH 범위를 확인한 뒤에만 apply
TF_VAR_enable_redis_ha=true terraform -chdir=infra/aws apply -input=false
./infra/aws/render-hosts-env.sh
./load-test/run-stage.sh v7 --hosts-env infra/aws/hosts.env --ssh-key ~/.ssh/id_ed25519
```

V7 측정은 B 1회와 primary SIGKILL→Sentinel 승격 1회다. replica 2대 동기화와 Sentinel의 replica·peer 발견이 끝나기 전에는 장애를 주입하지 않는다. 결과·상태·Grafana 원본을 커밋·푸시한 직후 `TF_VAR_enable_redis_ha=true terraform -chdir=infra/aws destroy -input=false`를 실행하고 원생 API 잔존 0개와 측정용 IAM 키 삭제를 확인한다. `movie-loadtest` 프로필은 V6 직후 삭제된 키를 새로 발급해 설정해야 한다. 비밀 값은 저장소에 적지 않는다.
