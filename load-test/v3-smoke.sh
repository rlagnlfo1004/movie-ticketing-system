#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE=http://localhost:8000/api/v1
TOKEN="redis-$(date +%s)-$$"
KEY="redis-key-$(date +%s)-$$"
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
  if [[ "$(docker compose -f "$ROOT/docker-compose.yml" exec -T redis redis-cli SISMEMBER 'seat:{1}:reserved' "$SEAT" | tr -d '\r')" == 1 ]]; then
    for persisted in {1..15}; do
      count="$(docker compose -f "$ROOT/docker-compose.yml" exec -T -e MYSQL_PWD=root mysql \
        mysql -uroot -N -B movie_seats -e "SELECT COUNT(*) FROM reservation_seats WHERE screening_id='1' AND seat_no='$SEAT'" | tr -d '\r')"
      if [[ "$count" == 1 ]]; then
        echo "V3 Redis confirmation and Pub/Sub DB persistence passed"
        exit 0
      fi
      sleep 1
    done
    echo "Worker did not persist Redis confirmation" >&2
    exit 1
  fi
  sleep 1
done
echo "Redis seat was not reserved" >&2
exit 1
