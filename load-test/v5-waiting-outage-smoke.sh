#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE=http://localhost:8000/api/v1
TOKEN="active-$(date +%s)-$$"
KEY="active-key-$(date +%s)-$$"
for attempt in {1..30}; do
  if curl -fsS -o /dev/null http://localhost:8081/actuator/health 2>/dev/null; then break; fi
  if [[ "$attempt" -eq 30 ]]; then echo "reservation-api did not become healthy" >&2; exit 1; fi
  sleep 1
done
SEAT="$(curl -fsS "$BASE/screenings/1/seats" | jq -r '[.seats[] | select(.status=="AVAILABLE") | .seatNo][0]')"
test "$SEAT" != null
docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli \
  SET "waiting:screening:1:active:$TOKEN" "$TOKEN" EX 600 >/dev/null

restore_waiting() {
  docker compose -f "$ROOT/docker-compose.yml" start waiting-api-1 waiting-api-2 waiting-api-3 >/dev/null
}
trap restore_waiting EXIT
docker compose -f "$ROOT/docker-compose.yml" stop waiting-api-1 waiting-api-2 waiting-api-3 >/dev/null
"$ROOT/load-test/snapshot.sh" v5-lua-active waiting-down-before >/dev/null
status="$(curl -sS -o /tmp/v5-hold-response.json -w '%{http_code}' -X POST "$BASE/screenings/1/holds" \
  -H 'Content-Type: application/json' -H "Queue-Token: $TOKEN" \
  -H "Idempotency-Key: $KEY" -d "{\"seats\":[\"$SEAT\"]}")"
"$ROOT/load-test/snapshot.sh" v5-lua-active waiting-down-after >/dev/null
if [[ "$status" != 201 ]]; then
  echo "Expected 201 with waiting-api stopped, got $status" >&2
  exit 1
fi
hold_id="$(jq -r .holdId /tmp/v5-hold-response.json)"
stored="$(docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli GET "seat:{1}:hold:$SEAT" | tr -d '\r')"
test "$stored" = "$hold_id"
test "$(jq -r .activeKeyCount "$ROOT/docs/evidence/v5-lua-active/waiting-down-after.json")" -gt 0
inactive="$(curl -sS -o /tmp/v5-inactive-response.json -w '%{http_code}' -X POST "$BASE/screenings/1/holds" \
  -H 'Content-Type: application/json' -H "Queue-Token: inactive-$(date +%s)-$$" \
  -H "Idempotency-Key: inactive-key-$(date +%s)-$$" -d "{\"seats\":[\"$SEAT\"]}")"
test "$inactive" = 403
test "$(jq -r .code /tmp/v5-inactive-response.json)" = NOT_ADMITTED
echo "V5 admitted token held $SEAT while every waiting-api was stopped"
