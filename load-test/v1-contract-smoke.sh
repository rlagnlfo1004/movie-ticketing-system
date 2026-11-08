#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE=http://localhost:8000/api/v1
TOKEN_A="contract-a-$(date +%s)-$$"
TOKEN_B="contract-b-$(date +%s)-$$"
KEY_A="contract-key-a-$(date +%s)-$$"
KEY_B="contract-key-b-$(date +%s)-$$"
SEAT="J-$((10 + (RANDOM % 20)))"
NEXT="J-$((${SEAT#J-} + 1))"

for attempt in {1..30}; do
  if curl -fsS -o /dev/null http://localhost:8081/actuator/health 2>/dev/null; then break; fi
  if [[ "$attempt" -eq 30 ]]; then echo "reservation-api did not become healthy" >&2; exit 1; fi
  sleep 1
done
for token in "$TOKEN_A" "$TOKEN_B"; do
  docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli \
    SET "waiting:screening:1:active:$token" "$token" EX 600 >/dev/null
done

first="$(curl -fsS -X POST "$BASE/screenings/1/holds" \
  -H 'Content-Type: application/json' -H "Queue-Token: $TOKEN_A" -H "Idempotency-Key: $KEY_A" \
  -d "{\"seats\":[\"$SEAT\"]}")"
replayed="$(curl -fsS -X POST "$BASE/screenings/1/holds" \
  -H 'Content-Type: application/json' -H "Queue-Token: $TOKEN_A" -H "Idempotency-Key: $KEY_A" \
  -d "{\"seats\":[\"$SEAT\"]}")"
test "$(jq -r .holdId <<<"$first")" = "$(jq -r .holdId <<<"$replayed")"

status="$(curl -sS -o /tmp/v1-conflict.json -w '%{http_code}' -X POST "$BASE/screenings/1/holds" \
  -H 'Content-Type: application/json' -H "Queue-Token: $TOKEN_B" -H "Idempotency-Key: $KEY_B" \
  -d "{\"seats\":[\"$NEXT\",\"$SEAT\"]}")"
test "$status" = 409
test "$(jq -r .code /tmp/v1-conflict.json)" = SEAT_TAKEN
states="$(curl -fsS "$BASE/screenings/1/seats")"
test "$(jq -r --arg seat "$NEXT" '.seats[] | select(.seatNo==$seat) | .status' <<<"$states")" = AVAILABLE
echo "V1 replay and all-or-nothing hold smoke passed"
