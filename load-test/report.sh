#!/usr/bin/env bash
set -euo pipefail
if [[ "$#" -lt 2 || "$#" -gt 3 ]]; then
  echo "usage: report.sh <stage> <run> [profile]" >&2
  exit 2
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 "$ROOT/load-test/report.py" "$@"
