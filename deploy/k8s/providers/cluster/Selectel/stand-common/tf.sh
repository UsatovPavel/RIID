#!/usr/bin/env bash
# Общая обёртка обоих стендов Selectel: подставляет учётные данные из
# deploy/k8s/config/.env в terraform через TF_VAR_*, чтобы ни один секрет не
# попал в tfvars или в историю шелла.
#
# Usage: tf.sh <module-dir> <terraform args...>
#
# Каталог модуля передаётся аргументом, а не вычисляется из BASH_SOURCE:
# скрипт один на два модуля, и звать его должен Makefile модуля.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MODULE_DIR="${1:-}"
if [[ -z "$MODULE_DIR" ]]; then
  echo "tf.sh: module directory is required (usage: tf.sh <module-dir> <terraform args...>)" >&2
  exit 2
fi
shift

if ! MODULE_DIR="$(cd "$MODULE_DIR" 2>/dev/null && pwd)"; then
  echo "tf.sh: module directory not found: $1" >&2
  exit 2
fi

# Ошибиться каталогом легко (цели зовутся из разных мест), а terraform в чужой
# папке молча сделает не тот план.
if ! compgen -G "$MODULE_DIR/*.tf" >/dev/null; then
  echo "tf.sh: no .tf files in $MODULE_DIR — is it a terraform module?" >&2
  exit 2
fi

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

exec "$TERRAFORM" -chdir="$MODULE_DIR" "$@"
