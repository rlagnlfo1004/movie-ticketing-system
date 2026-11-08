#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOKEN="closed-$(date +%s)-$$"
KEY="closed-key-$(date +%s)-$$"
SEAT="I-$((10 + (RANDOM % 20)))"

for attempt in {1..30}; do
  if curl -fsS -o /dev/null http://localhost:8081/actuator/health 2>/dev/null; then break; fi
  if [[ "$attempt" -eq 30 ]]; then echo "reservation-api did not become healthy" >&2; exit 1; fi
  sleep 1
done
docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli \
  SET "waiting:screening:1:active:$TOKEN" "$TOKEN" EX 600 >/dev/null
hold="$(curl -fsS -X POST http://localhost:8000/api/v1/screenings/1/holds \
  -H 'Content-Type: application/json' -H "Queue-Token: $TOKEN" \
  -H "Idempotency-Key: $KEY" -d "{\"seats\":[\"$SEAT\"]}")"
payment_id="$(jq -r .paymentId <<<"$hold")"
curl -fsS -X POST "http://localhost:8082/test/payments/$payment_id/approve" \
  -H 'Content-Type: application/json' \
  -d '{"amount":15000,"webhookUrl":"http://reservation-api:8081/api/v1/webhooks/portone"}' >/dev/null

"$ROOT/load-test/snapshot.sh" v1-db app-closed
test "$(jq -r --arg id "$payment_id" '.pgPaidWithoutReservation[] | select(.paymentId==$id) | .localStatus' "$ROOT/docs/evidence/v1-db/app-closed.json")" = READY
echo "V1 app-close gap captured: PG PAID, local READY, no reservation"
