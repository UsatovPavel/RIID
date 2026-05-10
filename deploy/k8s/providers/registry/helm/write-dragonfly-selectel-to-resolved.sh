#!/usr/bin/env bash
# Materialize Dragonfly Helm values fragment into deploy/k8s/.resolved/ (Selectel layout).
#
# Args:
#   $1 — deploy/k8s root (directory containing config/ and .resolved/)
#   $2 — path to config/imagelist/selectel.yaml
#   $3 — optional env file (REGISTRY_SELECTEL_ID / REGISTRY_LOGIN_HOST / DRAGONFLY_*), same as imagelist
set -euo pipefail

K8S_ROOT="${1:?deploy/k8s root}"
SELECTEL_YAML="${2:?selectel.yaml path}"
ENV_FILE="${3:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EMIT="${SCRIPT_DIR}/emit_dragonfly_selectel_fragment.py"
OUT="${K8S_ROOT}/.resolved/registry/helm/dragonfly-values-selectel.yaml"

[[ -f "${SELECTEL_YAML}" ]] || {
  echo "$0: missing ${SELECTEL_YAML}" >&2
  exit 1
}
[[ -f "${EMIT}" ]] || {
  echo "$0: missing ${EMIT}" >&2
  exit 1
}
command -v yq >/dev/null 2>&1 || {
  echo "$0: yq is required" >&2
  exit 1
}

if [[ "$(yq e '.infra.images["dragonfly.manager"] // ""' "${SELECTEL_YAML}")" == "" ]]; then
  echo "$0: skip (no dragonfly.manager in ${SELECTEL_YAML})" >&2
  exit 0
fi

mkdir -p "$(dirname "${OUT}")"
if [[ -n "${ENV_FILE}" && -f "${ENV_FILE}" && -r "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

python3 "${EMIT}" "${SELECTEL_YAML}" >"${OUT}"
echo "$0: wrote ${OUT}" >&2
