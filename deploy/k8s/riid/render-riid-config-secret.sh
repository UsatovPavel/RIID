#!/usr/bin/env bash
# Собрать Secret riid-config-secret из существующего configmap.yaml + локального .env (без копипасты YAML).
# Требуется: kubectl, yq (https://github.com/mikefarah/yq) v4.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIGMAP_YAML="${RIID_CONFIGMAP_YAML:-$SCRIPT_DIR/configmap.yaml}"
ENV_FILE="${RIID_ENV_FILE:-$SCRIPT_DIR/.env}"
APPLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply|-a) APPLY=1; shift ;;
    -h|--help)
      echo "Usage: $0 [--apply]   # stdout YAML, or --apply: kubectl apply -f -"
      echo "Env: RIID_CONFIGMAP_YAML, RIID_ENV_FILE (default: same dir as script)"
      echo "File .env: RIID_DOCKERHUB_USER, RIID_DOCKERHUB_TOKEN (or _PASSWORD)"
      exit 0
      ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

command -v yq >/dev/null 2>&1 || {
  echo "yq (v4) is required, e.g. https://github.com/mikefarah/yq" >&2
  exit 1
}
command -v kubectl >/dev/null 2>&1 || { echo "kubectl is required" >&2; exit 1; }
[[ -f "$CONFIGMAP_YAML" ]] || { echo "ConfigMap not found: $CONFIGMAP_YAML" >&2; exit 1; }
[[ -f "$ENV_FILE" ]] || { echo "No .env (copy from .env.example): $ENV_FILE" >&2; exit 1; }

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${RIID_DOCKERHUB_USER:?Set RIID_DOCKERHUB_USER in $ENV_FILE}"
_PASSWORD="${RIID_DOCKERHUB_TOKEN:-${RIID_DOCKERHUB_PASSWORD:-}}"
: "${_PASSWORD:?Set RIID_DOCKERHUB_TOKEN (or RIID_DOCKERHUB_PASSWORD) in $ENV_FILE}"

export _RIID_HUB_USER="$RIID_DOCKERHUB_USER"
export _RIID_HUB_PASS="$_PASSWORD"

TMP_INNER="$(mktemp)"
OUT="$(mktemp)"
trap 'rm -f "$TMP_INNER" "$OUT"' EXIT

# Единственный источник структуры — data.config.yaml в репозитории
yq e '.data["config.yaml"]' "$CONFIGMAP_YAML" \
  | yq e '(.client.registries[] | select(.host == "registry-1.docker.io") | .credentials) = {"username": strenv(_RIID_HUB_USER), "password": strenv(_RIID_HUB_PASS)}' - \
  > "$TMP_INNER"

kubectl create secret generic riid-config-secret \
  --from-file="config.yaml=$TMP_INNER" \
  -n riid-system \
  --dry-run=client -o yaml > "$OUT"

if [[ "$APPLY" -eq 1 ]]; then
  kubectl apply -f "$OUT"
  echo "Applied Secret riid-config-secret in riid-system. Restart RIID: kubectl -n riid-system rollout restart daemonset riid" >&2
else
  cat "$OUT"
fi
