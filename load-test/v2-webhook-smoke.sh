#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE=http://localhost:8000/api/v1
TOKEN="webhook-$(date +%s)-$$"
KEY="webhook-key-$(date +%s)-$$"

for attempt in {1..30}; do
  if curl -fsS -o /dev/null http://localhost:8081/actuator/health 2>/dev/null; then break; fi
  if [[ "$attempt" -eq 30 ]]; then echo "reservation-api did not become healthy" >&2; exit 1; fi
  sleep 1
done
SEAT="$(curl -fsS "$BASE/screenings/1/seats" | jq -r '[.seats[] | select(.status=="AVAILABLE") | .seatNo][0]')"
test "$SEAT" != null
docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli \
  SET "waiting:screening:1:active:$TOKEN" "$TOKEN" EX 600 >/dev/null
hold="$(curl -fsS -X POST "$BASE/screenings/1/holds" \
  -H 'Content-Type: application/json' -H "Queue-Token: $TOKEN" \
  -H "Idempotency-Key: $KEY" -d "{\"seats\":[\"$SEAT\"]}")"
payment_id="$(jq -r .paymentId <<<"$hold")"
curl -fsS -X POST "http://localhost:8082/test/payments/$payment_id/approve" \
  -H 'Content-Type: application/json' \
  -d '{"amount":15000,"webhookUrl":"http://reservation-api:8081/api/v1/webhooks/portone"}' >/dev/null
for attempt in {1..15}; do
  mine="$(curl -fsS "$BASE/screenings/1/reservations/me" -H "Queue-Token: $TOKEN")"
  if [[ "$(jq -r .status <<<"$mine")" == CONFIRMED ]]; then
    replay="$(curl -fsS -X POST "$BASE/payments/$payment_id/complete" -H 'Content-Type: application/json' -d '{}')"
    test "$(jq -r .reservationId <<<"$replay")" = "$(jq -r .reservationId <<<"$mine")"
    echo "V2 signed webhook confirmed payment without client completion"
    exit 0
  fi
  sleep 1
done
echo "Webhook did not confirm $payment_id" >&2
exit 1
