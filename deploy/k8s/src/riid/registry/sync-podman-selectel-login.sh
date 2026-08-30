#!/usr/bin/env bash
# На всех worker-нодах через podman-node выполняется podman login. В RIID-образе
# клиент podman отсутствует.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ENV_FILE="${ENV_FILE:-$K8S_DIR/config/.env}"
PROFILE_YAML="${RIID_REGISTRY_SELECTEL_PROFILE:-$K8S_DIR/providers/registry/client/selectel.yaml}"
NS="${RIID_NAMESPACE:-riid-system}"
CONTAINER="${PODMAN_NODE_CONTAINER:-installer}"

command -v kubectl >/dev/null 2>&1 || { echo "$0: kubectl required" >&2; exit 1; }
command -v yq >/dev/null 2>&1 || { echo "$0: yq (v4) required" >&2; exit 1; }
[[ -f "$ENV_FILE" ]] || { echo "$0: missing $ENV_FILE" >&2; exit 1; }
[[ -f "$PROFILE_YAML" ]] || { echo "$0: missing $PROFILE_YAML" >&2; exit 1; }

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${RIID_SELECTEL_USER:?Set RIID_SELECTEL_USER in $ENV_FILE}"
_PASS="${RIID_SELECTEL_TOKEN:-${RIID_SELECTEL_PASSWORD:-}}"
: "${_PASS:?Set RIID_SELECTEL_TOKEN (or RIID_SELECTEL_PASSWORD) in $ENV_FILE}"

REG_HOST="$(yq e '.registries[0].host' "$PROFILE_YAML" | tr -d '\r')"
[[ -n "$REG_HOST" && "$REG_HOST" != "null" ]] || {
  echo "$0: could not read .registries[0].host from $PROFILE_YAML" >&2
  exit 1
}

pods="$(kubectl -n "$NS" get pods -l 'app.kubernetes.io/name=podman-node' \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.phase}{"\n"}{end}' \
  | awk '$2=="Running" {print $1}')"

if [[ -z "$(echo "$pods" | tr -d '\n')" ]]; then
  echo "$0: no Running podman-node pods in $NS; skip podman login" >&2
  exit 0
fi

failed=0
while IFS= read -r pod; do
  [[ -z "$pod" ]] && continue
  if ! printf '%s\n' "$_PASS" | kubectl -n "$NS" exec -i -c "$CONTAINER" "$pod" -- \
    chroot /host podman login "$REG_HOST" --username "$RIID_SELECTEL_USER" --password-stdin >/dev/null; then
    echo "$0: host podman login failed through pod=$pod" >&2
    failed=1
  else
    echo "$0: host podman login ok through pod=$pod host=$REG_HOST" >&2
  fi
done <<<"$pods"

exit "$failed"
