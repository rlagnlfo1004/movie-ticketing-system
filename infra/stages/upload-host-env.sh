#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 4 ]]; then
  echo "usage: upload-host-env.sh <hosts.env> <stage-profile> <output-dir> <ssh-key>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HOSTS_FILE="$1"
PROFILE="$2"
OUTPUT_DIR="$3"
SSH_KEY="$4"
SSH_USER="${SSH_USER:-ec2-user}"
if [[ ! "$SSH_USER" =~ ^[A-Za-z0-9_-]+$ ]]; then
  echo "Invalid SSH_USER" >&2
  exit 2
fi
read_host_value() {
  local wanted="$1" key value
  while IFS='=' read -r key value; do
    if [[ "$key" == "$wanted" ]]; then
      printf '%s' "$value"
      return
    fi
  done < "$HOSTS_FILE"
}

python3 "$ROOT/infra/stages/build-host-env.py" "$HOSTS_FILE" "$PROFILE" "$OUTPUT_DIR" >/dev/null
for spec in \
  "lg:LG_PUBLIC_IP" "edge:EDGE_PUBLIC_IP" "app-waiting:APP_WAITING_PUBLIC_IP" \
  "app-reservation:APP_RESERVATION_PUBLIC_IP" "redis:REDIS_PUBLIC_IP" \
  "mysql:MYSQL_PUBLIC_IP" "mon:MON_PUBLIC_IP"; do
  host="${spec%%:*}"
  key="${spec#*:}"
  ip="$(read_host_value "$key")"
  if [[ ! "$ip" =~ ^[0-9]+(\.[0-9]+){3}$ ]]; then
    echo "Missing or invalid $key" >&2
    exit 2
  fi
  if [[ "${UPLOAD_DRY_RUN:-0}" == 1 ]]; then
    echo "Would upload $host.secret.env to $host ($ip)"
    continue
  fi
  ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -i "$SSH_KEY" \
    "$SSH_USER@$ip" 'mkdir -p ~/movie-ticketing && chmod 700 ~/movie-ticketing'
  scp -q -o BatchMode=yes -o StrictHostKeyChecking=accept-new -i "$SSH_KEY" \
    "$OUTPUT_DIR/$host.secret.env" "$SSH_USER@$ip:movie-ticketing/.env"
  ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -i "$SSH_KEY" \
    "$SSH_USER@$ip" 'chmod 600 ~/movie-ticketing/.env'
  echo "Uploaded $host.env"
done
