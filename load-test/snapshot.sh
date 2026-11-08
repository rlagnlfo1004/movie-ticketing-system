#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 2 ]]; then
  echo "usage: snapshot.sh <stage> <moment>" >&2
  exit 2
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 "$ROOT/load-test/snapshot.py" "$1" "$2"
