#!/usr/bin/env bash
set -euo pipefail
for setting in /sys/kernel/mm/transparent_hugepage/enabled /sys/kernel/mm/transparent_hugepage/defrag; do
  if [[ -w "$setting" ]]; then
    echo never > "$setting"
  fi
done
