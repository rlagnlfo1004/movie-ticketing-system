#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCENARIO="${SCENARIO:-A}"
SCREENING_ID="${SCREENING_ID:-1}"
STAGE_SECONDS="${STAGE_SECONDS:-60}"
SMOKE="${SMOKE:-0}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d_%H%M%S)}"
STAGE="${STAGE:-local}"
RESULT_DIR="$ROOT/load-test/results/$STAGE/$RUN_ID"
export STAGE RUN_ID

if [[ "$SCENARIO" != A && "$SCENARIO" != B ]]; then
  echo "SCENARIO must be A or B" >&2
  exit 2
fi
mkdir -p "$RESULT_DIR"
docker compose -f "$ROOT/docker-compose.yml" up -d --build \
  redis mysql mock-pg waiting-api-1 waiting-api-2 waiting-api-3 reservation-api nginx \
  prometheus grafana renderer node-exporter mysqld-exporter redis-exporter nginx-exporter

if [[ "$SCENARIO" == B ]]; then
  SCREENING_ID="$SCREENING_ID" STAGE_SECONDS="$STAGE_SECONDS" \
    python3 "$ROOT/load-test/seed-admissions.py" | \
    docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli --pipe
fi

START_SECONDS="$(date +%s)"
python3 "$ROOT/load-test/capture.py" snapshot "$STAGE" "$RUN_ID" before
set +e
docker compose -f "$ROOT/docker-compose.yml" --profile load-test run --rm \
  -v "$RESULT_DIR:/results" k6 run -o experimental-prometheus-rw \
  -e "SCENARIO=$SCENARIO" -e "SCREENING_ID=$SCREENING_ID" \
  -e "STAGE_SECONDS=$STAGE_SECONDS" -e "SMOKE=$SMOKE" \
  --tag "testid=$RUN_ID" \
  --summary-export=/results/summary.json /load-test/load-test.js
K6_STATUS=$?
set -e
sleep 5
END_SECONDS="$(date +%s)"
"$ROOT/load-test/capture.sh" "$STAGE" "$RUN_ID" "$START_SECONDS" "$END_SECONDS"

echo "Result: $RESULT_DIR/summary.json"
exit "$K6_STATUS"
