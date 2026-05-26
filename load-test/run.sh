#!/bin/bash
set -e

SCREENING_ID="${SCREENING_ID:-1}"
COMPOSE_FILE="$(dirname "$0")/../docker-compose.yml"
PROJECT_DIR="$(dirname "$0")/.."

echo "=============================="
echo " Movie Ticketing Load Test"
echo " SCREENING_ID: $SCREENING_ID"
echo "=============================="

# Step 1. 인프라 기동
echo ""
echo "[1/3] 도커 컨테이너 기동 중..."
docker compose -f "$COMPOSE_FILE" up -d --remove-orphans

# Step 2. nginx가 응답할 때까지 대기 (waiting-api 3개 + reservation-api healthcheck 통과 후 기동)
echo ""
echo "[2/3] 서비스 헬스 대기 중 (최대 3분)..."

TIMEOUT=180
ELAPSED=0
until curl -s -o /dev/null "http://localhost:8000/api/v1/screenings/${SCREENING_ID}/queue/status?token=probe" 2>/dev/null; do
  if [ $ELAPSED -ge $TIMEOUT ]; then
    echo "[ERROR] 서비스가 ${TIMEOUT}초 내에 기동되지 않았습니다."
    docker compose -f "$COMPOSE_FILE" logs --tail=30
    exit 1
  fi
  echo "  대기 중... (${ELAPSED}s)"
  sleep 5
  ELAPSED=$((ELAPSED + 5))
done

echo "  서비스 준비 완료!"

# Step 3. k6 부하 테스트 실행 (Docker 네트워크 내부)
echo ""
echo "[3/3] k6 부하 테스트 시작 (Docker 내부 네트워크)..."

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULT_DIR="$(dirname "$0")/results"
mkdir -p "$RESULT_DIR"

SCREENING_ID="$SCREENING_ID" docker compose -f "$COMPOSE_FILE" --profile load-test run --rm \
  -v "$(realpath "$RESULT_DIR"):/results" \
  k6 run \
  --summary-export=/results/summary_${TIMESTAMP}.json \
  /load-test/load-test.js

echo ""
echo "[완료] 결과 저장됨:"
echo "  - 요약: $RESULT_DIR/summary_${TIMESTAMP}.json"

echo ""
echo "[완료] 부하 테스트 종료."
