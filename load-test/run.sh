#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCENARIO="${SCENARIO:-A}"
SCREENING_ID="${SCREENING_ID:-1}"
STAGE_SECONDS="${STAGE_SECONDS:-60}"
SMOKE="${SMOKE:-0}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d_%H%M%S)}"
RESULT_DIR="$ROOT/load-test/results/${STAGE:-local}/$RUN_ID"

if [[ "$SCENARIO" != A && "$SCENARIO" != B ]]; then
  echo "SCENARIO must be A or B" >&2
  exit 2
fi
mkdir -p "$RESULT_DIR"
docker compose -f "$ROOT/docker-compose.yml" up -d --build redis mysql mock-pg waiting-api-1 waiting-api-2 waiting-api-3 reservation-api nginx

if [[ "$SCENARIO" == B ]]; then
  SCREENING_ID="$SCREENING_ID" STAGE_SECONDS="$STAGE_SECONDS" \
    python3 "$ROOT/load-test/seed-admissions.py" | \
    docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli --pipe
fi

docker compose -f "$ROOT/docker-compose.yml" --profile load-test run --rm \
  -v "$RESULT_DIR:/results" k6 run \
  -e "SCENARIO=$SCENARIO" -e "SCREENING_ID=$SCREENING_ID" \
  -e "STAGE_SECONDS=$STAGE_SECONDS" -e "SMOKE=$SMOKE" \
  --summary-export=/results/summary.json /load-test/load-test.js

echo "Result: $RESULT_DIR/summary.json"
