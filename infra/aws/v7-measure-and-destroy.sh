#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODE="${1:---plan-only}"
if [[ "$MODE" != --plan-only && "$MODE" != --run ]]; then
  echo "usage: v7-measure-and-destroy.sh [--plan-only|--run]" >&2
  exit 2
fi
PROFILE=movie-loadtest
ACCOUNT=976071095824
SSH_KEY="${V7_SSH_KEY:-$HOME/.ssh/id_ed25519}"
PLAN_FILE="$ROOT/.local/aws/v7.tfplan"
AUDIT_FILE="$ROOT/docs/evidence/aws-destroy-check-v7-$(date -u +%Y%m%d).json"
export TF_VAR_enable_redis_ha=true
export TF_VAR_operator_cidr="${OPERATOR_CIDR:-$(curl -fsS https://checkip.amazonaws.com)/32}"

if [[ ! -f "$SSH_KEY" || ! -f "$SSH_KEY.pub" ]]; then
  echo "V7 SSH key pair is missing" >&2
  exit 1
fi
ACTUAL_ACCOUNT="$(aws sts get-caller-identity --profile "$PROFILE" --region ap-northeast-2 --query Account --output text)"
if [[ "$ACTUAL_ACCOUNT" != "$ACCOUNT" ]]; then
  echo "Unexpected AWS account: $ACTUAL_ACCOUNT" >&2
  exit 1
fi
QUOTA="$(aws service-quotas get-service-quota --profile "$PROFILE" --region ap-northeast-2 \
  --service-code ec2 --quota-code L-1216C47A --query Quota.Value --output text)"
python3 - "$QUOTA" <<'PY'
import sys
if float(sys.argv[1]) < 30:
    raise SystemExit("V7 requires 30 Standard vCPU quota")
PY

mkdir -p "$ROOT/.local/aws"
terraform -chdir="$ROOT/infra/aws" init -backend=false -input=false >/dev/null
terraform -chdir="$ROOT/infra/aws" validate >/dev/null
if [[ -n "$(terraform -chdir="$ROOT/infra/aws" state list 2>/dev/null)" ]]; then
  echo "Terraform state is not empty before V7 apply" >&2
  exit 1
fi
terraform -chdir="$ROOT/infra/aws" plan -input=false -no-color -out="$PLAN_FILE" >/dev/null
terraform -chdir="$ROOT/infra/aws" show -json "$PLAN_FILE" | python3 -c '
import json,sys
plan=json.load(sys.stdin)
changes=[item for item in plan.get("resource_changes",[]) if item["change"]["actions"] != ["no-op"]]
if any(item["change"]["actions"] != ["create"] for item in changes):
    raise SystemExit("V7 plan contains non-create changes")
instances=[item for item in changes if item["type"] == "aws_instance"]
if len(instances) != 9:
    raise SystemExit(f"V7 plan expected 9 EC2 instances, got {len(instances)}")
forbidden={"aws_nat_gateway","aws_eip","aws_lb","aws_db_instance"}
if any(item["type"] in forbidden for item in changes):
    raise SystemExit("V7 plan contains an unplanned billable resource")
print(f"Plan: {len(changes)} creates, 9 EC2 hosts, 30 vCPU, no replacement or deletion")
'
python3 "$ROOT/infra/aws/cost.py" --v7 | python3 -c \
  'import json,sys; x=json.load(sys.stdin); print("Saved-price estimate: ${:.6f}/hour".format(x["total"]))'
if [[ "$MODE" == --plan-only ]]; then
  echo "Plan saved outside Git: $PLAN_FILE"
  exit 0
fi

cleanup_required=1
cleanup() {
  local original_status=$?
  trap - EXIT INT TERM
  if [[ "$cleanup_required" != 1 ]]; then
    exit "$original_status"
  fi
  echo "V7 cleanup: Terraform destroy starts now" >&2
  local destroyed=0
  for attempt in 1 2 3; do
    if terraform -chdir="$ROOT/infra/aws" destroy -auto-approve -input=false -no-color; then
      destroyed=1
      break
    fi
    sleep 5
  done
  if [[ "$destroyed" != 1 ]]; then
    echo "Terraform destroy failed; measurement IAM key is retained for cleanup" >&2
    exit 1
  fi
  local clean=0
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if "$ROOT/infra/aws/destroy-check.sh" "$AUDIT_FILE"; then
      clean=1
      break
    fi
    sleep 5
  done
  if [[ "$clean" != 1 || -n "$(terraform -chdir="$ROOT/infra/aws" state list)" ]]; then
    echo "V7 resources or Terraform state remain; IAM key is retained for cleanup" >&2
    exit 1
  fi
  local aws_key_id
  aws_key_id="$(aws configure get aws_access_key_id --profile "$PROFILE")"
  aws iam delete-access-key --profile "$PROFILE" --user-name movie-loadtest --access-key-id "$aws_key_id"
  python3 - <<'PY'
from pathlib import Path
path = Path.home() / ".aws/credentials"
lines = path.read_text().splitlines(keepends=True)
kept = []
target = False
for line in lines:
    stripped = line.strip()
    if stripped.startswith("[") and stripped.endswith("]"):
        target = stripped == "[movie-loadtest]"
    if target and stripped.split("=", 1)[0].strip() in ("aws_access_key_id", "aws_secret_access_key"):
        continue
    kept.append(line)
path.write_text("".join(kept))
path.chmod(0o600)
PY
  python3 "$ROOT/infra/stages/measurement-secrets.py" delete >/dev/null
  echo "V7 cleanup complete: native resources 0, state empty, measurement key deleted" >&2
  exit "$original_status"
}
trap cleanup EXIT INT TERM

terraform -chdir="$ROOT/infra/aws" apply -input=false -no-color "$PLAN_FILE"
"$ROOT/infra/aws/render-hosts-env.sh" >/dev/null
"$ROOT/load-test/run-stage.sh" v7 --hosts-env "$ROOT/infra/aws/hosts.env" --ssh-key "$SSH_KEY"
"$ROOT/load-test/report.sh" --all
cd "$ROOT"
git add -f load-test/results/v7-sentinel-fencing
git add docs/evidence/v7-sentinel-fencing load-test/results/README.md
git diff --cached --check
git commit -m 'V7 AWS Sentinel 승격 측정 원본 저장'
git push origin feat/seat-redesign
echo "V7 results pushed; cleanup will begin immediately" >&2
