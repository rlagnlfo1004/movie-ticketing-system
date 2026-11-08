#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
terraform -chdir="$ROOT" output -raw hosts_env > "$ROOT/hosts.env"
chmod 600 "$ROOT/hosts.env"
echo "$ROOT/hosts.env"
