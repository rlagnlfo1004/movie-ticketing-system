# AWS 측정 리소스 정리

- 계정: `976071095824`, 리전: `ap-northeast-2`, 프로필: `movie-loadtest`.
- 측정 결과를 `feat/seat-redesign`에 푸시한 뒤 Terraform destroy로 관리 리소스 22개를 삭제했다. 다시 실행한 destroy는 삭제 대상 0개였고 Terraform state 목록도 비어 있었다.
- [삭제 후 원본 검사](aws-destroy-check-20261110.json): EC2 인스턴스·EBS 볼륨·Elastic IP·NAT Gateway·VPC·서브넷·보안 그룹과 규칙·Internet Gateway·route table·EC2 키 페어가 모두 0개다.
- 측정용 IAM 액세스 키 삭제 명령이 성공했고, 측정용 Grafana 비밀 파일도 삭제했다. 비밀 값과 키 ID는 이 기록에 포함하지 않는다.
- Resource Groups Tagging API에는 과거 ARN 19개가 남았다. 이 API는 [과거에 태그가 달렸던 리소스도 반환](https://docs.aws.amazon.com/resourcegroupstagging/latest/APIReference/API_GetResources.html)하므로, 현재 리소스 판정에는 위 EC2 개별 조회와 Terraform state를 사용했다.
