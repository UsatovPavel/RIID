#!/usr/bin/env bash
# Собирает резолвнутые манифесты в deploy/k8s/.resolved/ (исходники в src/ и performance/ не меняются).
#
# Источники в git: строки «image: <logical_key>», ключ должен быть в catalog (.infra.images).
#
# Usage:
#   provider-apply.sh [--provider dockerhub|selectel|local] [--catalog PATH] [--root PATH] [--report PATH] [--dry-run]
#
# Выход: .resolved/src/**, .resolved/performance/**/*.yaml, при наличии Selectel imagelist —
# .resolved/registry/helm/dragonfly-values-selectel.yaml; отчёт provider-apply.tsv
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_K8S="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

PROVIDER="dockerhub"
CATALOG="${DEPLOY_K8S}/config/imagelist/dockerhub.yaml"
ROOT="${DEPLOY_K8S}"
REPORT="${SCRIPT_DIR}/output/provider-apply.tsv"
DRY_RUN=0
PY="${SCRIPT_DIR}/resolve_manifest_images.py"

usage() {
  echo "Usage: $0 [--provider dockerhub|selectel|local] [--catalog PATH] [--root PATH] [--report PATH] [--dry-run]" >&2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --provider)
      PROVIDER="$2"
      shift 2
      ;;
    --catalog)
      CATALOG="$2"
      shift 2
      ;;
    --root)
      ROOT="$2"
      shift 2
      ;;
    --report)
      REPORT="$2"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

RESOLVED="${ROOT}/.resolved"

command -v yq >/dev/null 2>&1 || {
  echo "$0: yq is required" >&2
  exit 1
}
command -v python3 >/dev/null 2>&1 || {
  echo "$0: python3 is required" >&2
  exit 1
}
[[ -f "$PY" ]] || {
  echo "$0: missing ${PY}" >&2
  exit 1
}

[[ -f "$CATALOG" ]] || {
  echo "$0: catalog not found: $CATALOG" >&2
  exit 1
}

mkdir -p "$(dirname "$REPORT")"
echo -e "file\timage_key\timage_resolved\tprovider" >"$REPORT"

HELM_WRITE="$(cd "${SCRIPT_DIR}/../helm" && pwd)/write-dragonfly-selectel-to-resolved.sh"

sync_resolved_tree() {
  [[ "$DRY_RUN" == "1" ]] && return 0
  rm -rf "${RESOLVED}"
  mkdir -p "${RESOLVED}/src"
  cp -a "${ROOT}/src/." "${RESOLVED}/src/"
  if [[ -d "${ROOT}/performance" ]]; then
    while IFS= read -r -d '' f; do
      rel="${f#"${ROOT}/performance/"}"
      mkdir -p "${RESOLVED}/performance/$(dirname "${rel}")"
      cp -a "$f" "${RESOLVED}/performance/${rel}"
    done < <(find "${ROOT}/performance" \( -name '*.yaml' -o -name '*.yml' \) -print0)
  fi
}

run_resolver() {
  local roots_write roots_scan write_flag relfix
  if [[ "$DRY_RUN" == "1" ]]; then
    roots_scan=("${ROOT}/src")
    [[ -d "${ROOT}/performance" ]] && roots_scan+=("${ROOT}/performance")
    python3 "${PY}" \
      --catalog "${CATALOG}" \
      --provider "${PROVIDER}" \
      --roots "${roots_scan[@]}" \
      --report "${REPORT}" \
      --rel-prefix "${ROOT}"
    return 0
  fi

  roots_write=("${RESOLVED}/src")
  [[ -d "${RESOLVED}/performance" ]] && roots_write+=("${RESOLVED}/performance")
  python3 "${PY}" \
    --catalog "${CATALOG}" \
    --provider "${PROVIDER}" \
    --roots "${roots_write[@]}" \
    --report "${REPORT}" \
    --rel-prefix "${RESOLVED}" \
    --write
}

main_apply() {
  case "${PROVIDER}" in
    dockerhub) ;;
    selectel | local)
      echo "$0: provider=${PROVIDER} not implemented yet (only dockerhub)." >&2
      exit 2
      ;;
    *)
      echo "$0: unknown provider: ${PROVIDER}" >&2
      exit 1
      ;;
  esac

  sync_resolved_tree
  if [[ "$DRY_RUN" != "1" ]]; then
    CONFIG_ENV_CAND="${ROOT}/config/.env"
    bash "${HELM_WRITE}" "${ROOT}" "${ROOT}/config/imagelist/selectel.yaml" "${CONFIG_ENV_CAND}"
  fi
  run_resolver

  if [[ "$DRY_RUN" == "1" ]]; then
    echo "$0: dry-run; catalog keys validated; report ${REPORT} (no ${RESOLVED}/)"
  else
    echo "$0: done; resolved tree ${RESOLVED} (sources under ${ROOT}/src unchanged); report ${REPORT}"
  fi
}

main_apply
