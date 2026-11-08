# 단계별 설정

`v1.env`부터 `v6.env`까지는 코드 태그와 함께 쓰는 비밀 없는 설정이다. `STAGE_CODE_TAG`가 배포할 git 태그를 가리킨다. `REDIS_CONFIG`가 실제 Redis 설정 파일을 고르고, `REDIS_APPENDONLY`·`REDIS_APPENDFSYNC`는 결과 보고서에 비교 근거로 남기는 값이다. 결제 모드는 측정일에 모의 PG이며 PortOne 실제 채널은 로컬 검증에서만 쓴다.

`HOLD_DURATION=30s`는 측정용이다. 앱 설정의 기본 선점 유지 시간은 5분이다. `SPRING_THREADS_VIRTUAL_ENABLED=true`가 기준선이고, 런북의 가상 스레드 비교 실행에서만 `false`로 덮어쓴다.

로컬 시작 순서:

```bash
python3 infra/stages/ensure-local-env.py
STAGE=v6-aof-reconcile STAGE_PROFILE=v6 SMOKE=1 ./load-test/run.sh
```

생성기는 기존 `.env` 값을 덮어쓰지 않고 필요한 좌석 예매 전용 키만 채운다. `.env`와 `.local/mysql-exporter.cnf`는 저장소에서 제외하고 권한을 600으로 둔다. `v6-always.env`는 `appendfsync always` 비교용 설정이다.

측정일에는 `measurement-secrets.py create`가 Grafana 비밀번호를 저장소 밖의 `~/.config/movie-ticketing/loadtest-grafana.env`에 만든다. 런북의 destroy 확인 뒤 `measurement-secrets.py delete`로 해당 파일을 없앤다. PortOne 값은 로컬 `~/.config/movie-ticketing/portone.env`에서만 읽고 AWS 호스트 env에는 넣지 않는다.

Terraform의 `hosts.env`가 나오면 `build-host-env.py <hosts.env> <단계 프로필> <출력 폴더>`가 일곱 호스트의 `*.secret.env`를 만든다. 앱은 사설 IP로 Redis·MySQL에 접속하며, AWS용 파일에는 PortOne 변수를 넣지 않는다. 런북(#20)이 이 파일들을 각 서버로 전달한다.
