#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
ACCOUNT="$(aws sts get-caller-identity --profile movie-loadtest --region ap-northeast-2 --query Account --output text)"
if [[ "$ACCOUNT" != 976071095824 ]]; then
  echo "Unexpected AWS account: $ACCOUNT" >&2
  exit 1
fi
OPERATOR_CIDR="${OPERATOR_CIDR:-$(curl -fsS https://checkip.amazonaws.com)/32}"
terraform -chdir="$ROOT" init -backend=false -input=false
terraform -chdir="$ROOT" validate
TF_VAR_operator_cidr="$OPERATOR_CIDR" terraform -chdir="$ROOT" plan -no-color -input=false
