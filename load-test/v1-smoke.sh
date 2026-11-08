#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCREENING_ID="${SCREENING_ID:-1}"
TOKEN="smoke-$(date +%s)-$$"
KEY="hold-$(date +%s)-$$"
SEAT="A-$((10 + (RANDOM % 20)))"
BASE="http://localhost:8000/api/v1"

for attempt in {1..30}; do
  if curl -fsS -o /dev/null http://localhost:8081/actuator/health 2>/dev/null; then break; fi
  if [[ "$attempt" -eq 30 ]]; then echo "reservation-api did not become healthy" >&2; exit 1; fi
  sleep 1
done

docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli \
  SET "waiting:screening:$SCREENING_ID:active:$TOKEN" "$TOKEN" EX 600 >/dev/null

hold="$(curl -fsS -X POST "$BASE/screenings/$SCREENING_ID/holds" \
  -H 'Content-Type: application/json' -H "Queue-Token: $TOKEN" \
  -H "Idempotency-Key: $KEY" -d "{\"seats\":[\"$SEAT\"]}")"
payment_id="$(jq -r .paymentId <<<"$hold")"
test "$(jq -r .amount <<<"$hold")" = 15000
test "$payment_id" != null

curl -fsS -X POST "http://localhost:8082/test/payments/$payment_id/approve" \
  -H 'Content-Type: application/json' \
  -d "{\"amount\":15000,\"webhookUrl\":\"http://reservation-api:8081/api/v1/webhooks/portone\"}" >/dev/null

complete="$(curl -fsS -X POST "$BASE/payments/$payment_id/complete" \
  -H 'Content-Type: application/json' -d '{}')"
test "$(jq -r .status <<<"$complete")" = CONFIRMED

mine="$(curl -fsS "$BASE/screenings/$SCREENING_ID/reservations/me" -H "Queue-Token: $TOKEN")"
test "$(jq -r .status <<<"$mine")" = CONFIRMED

seat_state="$(curl -fsS "$BASE/screenings/$SCREENING_ID/seats")"
test "$(jq -r --arg seat "$SEAT" '.seats[] | select(.seatNo==$seat) | .status' <<<"$seat_state")" = RESERVED

echo "V1 HTTP smoke passed: $SEAT confirmed"
