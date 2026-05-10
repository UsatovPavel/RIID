#!/usr/bin/env bash
# Генерирует deploy/k8s/config/imagelist/selectel.yaml и local.yaml из dockerhub.yaml.
#
# Env: PROVIDER_REGISTRY_ENV_FILE — для selectel (REGISTRY_SELECTEL_ID); если файла нет,
#      infra.images в selectel.yaml будет пустым.
#
# Usage:
#   ./imagelist-build-overlays.sh [dockerhub.yaml] [out-selectel.yaml] [out-local.yaml]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

DOCKERHUB="${1:-$K8S_ROOT/config/imagelist/dockerhub.yaml}"
OUT_SEL="${2:-$K8S_ROOT/config/imagelist/selectel.yaml}"
OUT_LOC="${3:-$K8S_ROOT/config/imagelist/local.yaml}"

_cand="${PROVIDER_REGISTRY_ENV_FILE:-}"
if [[ -z "${_cand}" ]]; then
  _cand="$K8S_ROOT/config/.env"
fi
ENV_FILE=""
if [[ -f "${_cand}" && -r "${_cand}" ]]; then
  ENV_FILE="${_cand}"
fi

# shellcheck source=/dev/null
source "${SCRIPT_DIR}/mapper-common.sh"

imagelist_build_overlays_from_dockerhub "${DOCKERHUB}" "${OUT_SEL}" "${OUT_LOC}" "${ENV_FILE}"

HELM_WRITE="${SCRIPT_DIR}/../helm/write-dragonfly-selectel-to-resolved.sh"
bash "${HELM_WRITE}" "${K8S_ROOT}" "${OUT_SEL}" "${ENV_FILE}"
