#!/usr/bin/env bash
# Thin wrapper: feeds Selectel credentials from deploy/k8s/config/.env into
# terraform as TF_VAR_* so that no secret is ever written to a tfvars file.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$(cd "$HERE/../../../../config" && pwd)/.env}"
TERRAFORM="${TERRAFORM:-terraform}"

# shellcheck source=load-env.inc.sh
. "$HERE/load-env.inc.sh"

if ! riid_load_env "$ENV_FILE"; then
  echo "tf.sh: $ENV_FILE not found — copy config/.env.example and fill it in" >&2
fi

export TF_VAR_account_id="${SELECTEL_ACCOUNT_ID:-}"
export TF_VAR_iam_username="${SELECTEL_IAM_USER:-}"
export TF_VAR_iam_password="${SELECTEL_IAM_PASSWORD:-}"
export TF_IN_AUTOMATION=1

# Not fatal: init/fmt/validate work without a project, plan/apply will complain.
if project_id="$(ENV_FILE="$ENV_FILE" "$HERE/resolve-project-id.sh" 2>/dev/null)"; then
  export TF_VAR_project_id="$project_id"
fi

[[ -n "${NODES:-}" ]] && export TF_VAR_nodes_count="$NODES"
[[ -n "${CLUSTER_NAME:-}" ]] && export TF_VAR_cluster_name="$CLUSTER_NAME"

exec "$TERRAFORM" -chdir="$HERE" "$@"
