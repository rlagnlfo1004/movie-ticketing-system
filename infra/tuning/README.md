# 측정 전 튜닝 기준선

측정일에는 V1~V6에서 앱 구현만 바꾸고 이 기준선은 그대로 둔다. 아래 값은 **측정 전 설정값**이며, 성능 결과가 아니다. 로컬 Compose는 서비스 DNS가 필요한 검증용 브리지 네트워크를 쓴다. AWS Linux 호스트에는 `build-stage-bundles.py`가 host 네트워크와 사설 IP를 반영한 Compose를 생성한다. 한 호스트의 waiting-api 세 프로세스는 8080·8082·8083 포트를 쓴다.

현재 V6 브랜치의 기본 Compose는 `redis-v6.conf`와 이름 붙인 `redis-data` 볼륨을 쓴다. 측정 런북은 단계 env의 `REDIS_CONFIG`로 실제 설정 파일을 고른다.
단계별 파일 선택과 가상 스레드 토글은 [`../stages/README.md`](../stages/README.md)를 따른다.

## OS와 Docker

- `net.core.somaxconn=16384`: 접속 폭주 때 accept 대기열이 먼저 포화되는 일을 줄인다.
- `net.ipv4.tcp_max_syn_backlog=16384`: 아직 연결이 성립하지 않은 요청을 더 오래 받는다.
- `net.ipv4.ip_local_port_range=10240 65535`: k6의 짧은 연결에서 사용할 임시 포트 범위를 넓힌다.
- `net.ipv4.tcp_tw_reuse=1`: 안전한 TIME_WAIT 소켓 재사용으로 포트 부족 가능성을 줄인다.
- `fs.file-max=2097152`: 호스트 전체 파일 디스크립터 한도를 높인다.
- `nofile=65535`: 서비스별 연결과 파일을 수용하도록 PAM 한도와 Compose 컨테이너 한도를 맞춘다.
- Redis 호스트의 THP는 `disable-thp.sh`로 끈다. THP로 인한 Redis 지연 변동을 줄인다.
- `network_mode: host`: AWS 측정에서 Docker NAT 오버헤드를 없앤다. 로컬 Docker Desktop에는 적용하지 않는다.
- Docker `json-file` 로그 `20m×3`: 로그가 디스크를 채우지 않도록 상한을 둔다. CPU·메모리 제한은 두지 않는다.
- 문제: 측정일 Amazon Linux 2023 AMI의 `curl-minimal`과 `curl`이 충돌해 user-data가 Docker 설치 전에 중단됐다. 해결: 기존 `curl-minimal`을 그대로 사용하고 `dnf install -y docker`만 실행하도록 수정했다. 실행 중인 일곱 호스트에서도 수정한 초기화 스크립트를 이어 실행해 Docker Compose, node_exporter, 시간 동기화와 MySQL 별도 볼륨 마운트를 확인했다.
- 문제: Amazon Linux의 기본 Docker Buildx 0.12.2는 Compose 5.6.0의 이미지 빌드 요구 버전(0.17 이상)보다 낮아 모의 PG 이미지 빌드가 중단됐다. 해결: Buildx 0.38.2 arm64 바이너리를 SHA-256 검증 후 설치하도록 user-data에 고정했다.
- 문제: 부하 발생기의 k6 컨테이너 계정이 호스트에 만든 결과 디렉터리에 `summary.json`을 쓸 수 없었다. 해결: 실행별 결과 디렉터리만 컨테이너가 쓸 수 있게 연 뒤 요약 원본을 수집한다.
- 문제: [첫 정식 A 시도](../../load-test/results/v1-db/20261109T141642Z-a1/README.md)에서 실행 중 VU 확장이 10초의 요청 등록 속도를 따라가지 못해 반복이 누락됐다. 해결: `lg`를 8 vCPU·16GiB로 늘리고 비스모크 A의 30,000 VU를 시작 전에 확보한다. 이 시도는 비교에서 제외한다.
- 문제: [Nginx 연결 한도 확장 뒤 시도](../../load-test/results/v1-db/20261109T144453Z-a1/README.md)에서는 waiting-api 호스트의 TCP listen 대기열이 넘쳐 Nginx가 세 upstream을 모두 일시적으로 사용 불가로 판단했다. 해결: 호스트의 `somaxconn`·SYN backlog를 16,384로, waiting-api Tomcat의 `max-connections`를 20,000, `accept-count`를 16,384로 높이고 Nginx의 일시적 upstream 제외를 껐다. 이 시도도 비교에서 제외한다.
- 문제: [listen overflow를 없앤 뒤 시도](../../load-test/results/v1-db/20261109T145313Z-a1/README.md)에서는 1초 주기의 대기 상태 조회가 폭주해 waiting-api 응답 시간 초과가 발생했다. 해결: 대기 중 조회 권장 간격을 5초로 늘리고, 첫 조회를 2~10초에 분산한 뒤 후속 조회에 ±20% 지터를 적용했다. 등록 3만 명/10초 조건은 유지한다. 이 시도도 비교에서 제외한다.
- 문제: 원격 Prometheus JSON에는 시계열이 있는데 Grafana PNG는 `No data`였다. 원격 host 네트워크의 Grafana 데이터소스가 Docker DNS 주소 `prometheus:9090`을 사용한 것이 원인이다. 해결: 원격 배포 묶음에서 `127.0.0.1:9090`으로 바꾸고, 매 실행 전에 Grafana 프록시를 통해 `up` 지표가 조회되는지 확인한다.

## Nginx와 JVM

- `worker_processes auto`: edge 호스트의 vCPU 수에 맞춰 worker를 띄운다.
- `worker_connections=65535`와 `worker_rlimit_nofile=65535`: 접속 수가 파일 한도에 먼저 걸리지 않게 한다.
- waiting-api upstream의 `max_fails=0`: 일시적 연결 실패가 세 인스턴스를 모두 사용 불가로 표시하는 일을 막는다.
- 문제: [VU 확장 후 정식 A 시도](../../load-test/results/v1-db/20261109T142741Z-a1/README.md)에서 Nginx 양쪽 worker가 `10240 worker_connections are not enough`를 기록했고, 대기열 등록 연결이 실패했다. 해결: worker당 연결 한도를 65,535로 높여 같은 부하 조건에서 다시 확인한다. 이 시도는 유효한 단계 비교에서 제외한다.
- upstream `keepalive` 100/32, `proxy_http_version 1.1`, 빈 `Connection` 헤더: 앱으로 향하는 연결을 재사용한다.
- `listen reuseport`: worker별 수신 소켓으로 accept 경합을 줄인다.
- `access_log off`: 측정 중 요청별 파일 쓰기 비용을 제외한다.
- JVM `-Xms512m -Xmx2g`, G1, 50ms pause 목표: 16GB 앱 호스트에 세 waiting-api와 한 reservation-api를 배치할 메모리 여지를 남긴다.
- 가상 스레드 활성화: 대기·예매 API의 블로킹 I/O에 쓰레드를 묶어 두지 않는다.
- Hikari 최대 100/최소 20, 연결 획득 제한 3초: DB 연결 수 500 안에서 대기 시간을 계측한다.
- 문제: Tomcat 사용 중 스레드 패널에 시계열이 없었다. 해결: 대기·예매 API에 `SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true`를 적용해 `/actuator/prometheus`에서 `tomcat_threads_busy_threads`가 출력되는지 확인했다. 가상 스레드 실행에서 값이 `-1`이면 사용 중 스레드 수로 해석하지 않는다.

## Redis와 MySQL

- Redis `maxclients=10000`: k6·API·worker 연결을 허용한다.
- Redis `tcp-backlog=511`: Redis 접속 대기열을 기준선으로 고정한다.
- Redis `hz=20`: 만료 키 정리 주기를 기본값보다 높인다.
- V1~V5 `save ""`, `appendonly no`: Redis 디스크 영속화 비용을 제외하고 DB/메시지 방식 차이를 본다.
- V6 `appendonly yes`, `appendfsync everysec`: 확정 기록을 AOF로 남기되 fsync를 초 단위로 묶는다. 런북이 `v6.env`에서 설정 파일을 고른다.
- 비교용 `appendfsync always`: 매 쓰기 fsync 비용을 따로 확인한다. 런북이 `v6-always.env`에서 설정 파일을 고른다.
- MySQL 버퍼 풀 로컬 3GB, AWS 22GB: 로컬 메모리와 AWS MySQL 호스트 32GB의 약 70%에 각각 맞춘다.
- MySQL redo log 로컬 1GB, AWS 2GB: 폭주 쓰기 중 체크포인트 빈도를 낮춘다.
- `innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`: 확정 커밋의 내구성 조건을 전 단계에서 동일하게 둔다.
- `max_connections=500`: Hikari와 exporter 연결을 받되 무제한 연결 경쟁은 막는다.
- `innodb_print_all_deadlocks=ON`: deadlock 원인을 MySQL 오류 로그에 남긴다.

로컬 확인: `docker compose up -d redis mysql nginx` 뒤 `redis-cli CONFIG GET appendonly`와 `SHOW VARIABLES`로 적용값을 읽는다. AWS에서는 `sysctl --system`, `limits.conf` 설치, THP 스크립트 실행을 Terraform user-data에서 처리한다.
