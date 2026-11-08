#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOKEN="expire-$(date +%s)-$$"
KEY="expire-key-$(date +%s)-$$"
SEAT="B-$((10 + (RANDOM % 20)))"
BASE="http://localhost:8000/api/v1"

for attempt in {1..30}; do
  if curl -fsS -o /dev/null http://localhost:8081/actuator/health 2>/dev/null; then break; fi
  if [[ "$attempt" -eq 30 ]]; then echo "reservation-api did not become healthy" >&2; exit 1; fi
  sleep 1
done

docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli \
  SET "waiting:screening:1:active:$TOKEN" "$TOKEN" EX 600 >/dev/null
hold="$(curl -fsS -X POST "$BASE/screenings/1/holds" \
  -H 'Content-Type: application/json' -H "Queue-Token: $TOKEN" \
  -H "Idempotency-Key: $KEY" -d "{\"seats\":[\"$SEAT\"]}")"
hold_id="$(jq -r .holdId <<<"$hold")"

docker compose -f "$ROOT/docker-compose.yml" exec -T -e MYSQL_PWD=root mysql mysql -uroot movie_seats \
  -e "UPDATE seat_holds SET held_until=NOW(6)-INTERVAL 1 SECOND WHERE hold_id='$hold_id'" >/dev/null
sleep 2

mine="$(curl -fsS "$BASE/screenings/1/reservations/me" -H "Queue-Token: $TOKEN")"
test "$(jq -r .status <<<"$mine")" = EXPIRED
seats="$(curl -fsS "$BASE/screenings/1/seats")"
test "$(jq -r --arg seat "$SEAT" '.seats[] | select(.seatNo==$seat) | .status' <<<"$seats")" = AVAILABLE
echo "V1 expiry smoke passed: $SEAT released"
