#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -ne 4 ]]; then
  echo "usage: capture.sh <stage> <run> <from-unix-seconds> <to-unix-seconds>" >&2
  exit 2
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 "$ROOT/load-test/capture.py" capture "$1" "$2" "$3" "$4"
