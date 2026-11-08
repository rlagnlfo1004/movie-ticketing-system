#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUTPUT="${1:-$ROOT/destroy-check.json}"
python3 "$ROOT/destroy-check.py" "$OUTPUT"
