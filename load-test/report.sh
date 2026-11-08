#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "${1:-}" == --all && "$#" -eq 1 ]]; then
  exec python3 "$ROOT/load-test/stage-report.py"
fi
if [[ "$#" -lt 2 || "$#" -gt 3 ]]; then
  echo "usage: report.sh <stage> <run> [profile] | --all" >&2
  exit 2
fi
python3 "$ROOT/load-test/report.py" "$@"
