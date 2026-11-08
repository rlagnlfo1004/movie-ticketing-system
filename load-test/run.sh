#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python3 "$ROOT/infra/stages/ensure-local-env.py"
SCENARIO="${SCENARIO:-A}"
SCREENING_ID="${SCREENING_ID:-1}"
STAGE_SECONDS="${STAGE_SECONDS:-60}"
SMOKE="${SMOKE:-0}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d_%H%M%S)}"
STAGE="${STAGE:-local}"
STAGE_PROFILE="${STAGE_PROFILE:-${STAGE%%-*}}"
STAGE_ENV="$ROOT/infra/stages/$STAGE_PROFILE.env"
if [[ -f "$STAGE_ENV" ]]; then
  while IFS= read -r line; do
    [[ "$line" == *=* ]] || continue
    key="${line%%=*}"
    value="${line#*=}"
    if [[ -z "${!key:-}" ]]; then export "$key=$value"; fi
  done < "$STAGE_ENV"
fi
RESULT_DIR="$ROOT/load-test/results/$STAGE/$RUN_ID"
export STAGE RUN_ID

if [[ "$SCENARIO" != A && "$SCENARIO" != B ]]; then
  echo "SCENARIO must be A or B" >&2
  exit 2
fi
mkdir -p "$RESULT_DIR"
if [[ "${SKIP_SETUP:-0}" != 1 ]]; then
  docker compose -f "$ROOT/docker-compose.yml" stop \
    waiting-api-1 waiting-api-2 waiting-api-3 reservation-api reservation-worker >/dev/null
  if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
    "$ROOT/gradlew" :waiting-api:bootJar :reservation-api:bootJar :reservation-worker:bootJar
  fi
  docker compose -f "$ROOT/docker-compose.yml" up -d --build \
    redis mysql mock-pg waiting-api-1 waiting-api-2 waiting-api-3 reservation-api reservation-worker nginx \
    prometheus grafana renderer node-exporter mysqld-exporter redis-exporter nginx-exporter
  for attempt in {1..60}; do
    if curl -fsS -o /dev/null http://localhost:8081/actuator/health/readiness 2>/dev/null; then break; fi
    if [[ "$attempt" -eq 60 ]]; then echo "reservation-api did not become ready" >&2; exit 1; fi
    sleep 1
  done
  docker compose -f "$ROOT/docker-compose.yml" exec -T nginx nginx -s reload
fi

if [[ "$SCENARIO" == B ]]; then
  SCREENING_ID="$SCREENING_ID" STAGE_SECONDS="$STAGE_SECONDS" \
    python3 "$ROOT/load-test/seed-admissions.py" | \
    docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli --pipe
fi

START_SECONDS="$(date +%s)"
python3 "$ROOT/load-test/capture.py" snapshot "$STAGE" "$RUN_ID" before
FAULT_PID=""
SNAPSHOT_PID=""
if [[ -n "${SNAPSHOT_MOMENT:-}" ]]; then
  (
    sleep "${SNAPSHOT_DELAY_SECONDS:-2}"
    "$ROOT/load-test/snapshot.sh" "$STAGE" "$RUN_ID-$SNAPSHOT_MOMENT"
  ) &
  SNAPSHOT_PID="$!"
fi
if [[ -n "${FAULT_KIND:-}" ]]; then
  python3 "$ROOT/load-test/fault.py" "$STAGE" "$RUN_ID" "$FAULT_KIND" \
    "${FAULT_DELAY_SECONDS:-5}" "${FAULT_OUTAGE_SECONDS:-30}" &
  FAULT_PID="$!"
fi
set +e
docker compose -f "$ROOT/docker-compose.yml" --profile load-test run --rm --no-deps \
  -v "$RESULT_DIR:/results" k6 run -o experimental-prometheus-rw \
  -e "SCENARIO=$SCENARIO" -e "SCREENING_ID=$SCREENING_ID" \
  -e "STAGE_SECONDS=$STAGE_SECONDS" -e "SMOKE=$SMOKE" \
  --tag "testid=$RUN_ID" \
  --summary-export=/results/summary.json /load-test/load-test.js
K6_STATUS=$?
set -e
if [[ -n "$FAULT_PID" ]]; then
  wait "$FAULT_PID" || K6_STATUS=1
fi
if [[ -n "$SNAPSHOT_PID" ]]; then
  wait "$SNAPSHOT_PID" || K6_STATUS=1
fi
sleep 5
END_SECONDS="$(date +%s)"
"$ROOT/load-test/capture.sh" "$STAGE" "$RUN_ID" "$START_SECONDS" "$END_SECONDS"
if [[ -f "$STAGE_ENV" ]]; then
  "$ROOT/load-test/report.sh" "$STAGE" "$RUN_ID" "$STAGE_PROFILE"
fi

echo "Result: $RESULT_DIR/summary.json"
exit "$K6_STATUS"
