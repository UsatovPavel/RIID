#!/usr/bin/env bash
# Build Secret riid-config-secret from configmap.yaml + registry profile YAML + .env credentials.
# Requires: kubectl, yq v4 (https://github.com/mikefarah/yq).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
CONFIGMAP_YAML="${RIID_CONFIGMAP_YAML:-$SCRIPT_DIR/configmap.yaml}"
ENV_FILE="${RIID_ENV_FILE:-$K8S_DIR/config/.env}"
PROFILE="${RIID_REGISTRY_PROFILE:-dockerhub}"
PROFILE_DIR="${RIID_REGISTRY_PROFILE_DIR:-$K8S_DIR/providers/registry/client}"
APPLY=0

usage() {
  echo "Usage: $0 [--apply] [--profile NAME]"
  echo "  NAME: dockerhub | selectel | local (file deploy/k8s/providers/registry/client/<NAME>.yaml must exist)"
  echo "Env: RIID_CONFIGMAP_YAML, RIID_ENV_FILE, RIID_REGISTRY_PROFILE, RIID_REGISTRY_PROFILE_DIR"
  echo "Credentials in .env:"
  echo "  dockerhub — RIID_DOCKERHUB_USER, RIID_DOCKERHUB_TOKEN (or RIID_DOCKERHUB_PASSWORD)"
  echo "  selectel  — RIID_SELECTEL_USER, RIID_SELECTEL_TOKEN (or RIID_SELECTEL_PASSWORD)"
  echo "  local     — credentials are not required"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apply|-a) APPLY=1; shift ;;
    --profile|-p)
      PROFILE="$2"
      shift 2
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage >&2; exit 1 ;;
  esac
done

command -v yq >/dev/null 2>&1 || {
  echo "yq (v4) is required, e.g. https://github.com/mikefarah/yq" >&2
  exit 1
}
command -v kubectl >/dev/null 2>&1 || { echo "kubectl is required" >&2; exit 1; }
[[ -f "$CONFIGMAP_YAML" ]] || { echo "ConfigMap not found: $CONFIGMAP_YAML" >&2; exit 1; }
[[ -f "$ENV_FILE" ]] || { echo "No .env (copy from .env.example): $ENV_FILE" >&2; exit 1; }

PROFILE_YAML="$PROFILE_DIR/${PROFILE}.yaml"
[[ -f "$PROFILE_YAML" ]] || {
  echo "Unknown registry profile '${PROFILE}': missing $PROFILE_YAML" >&2
  exit 1
}

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

case "$PROFILE" in
  dockerhub)
    : "${RIID_DOCKERHUB_USER:?Set RIID_DOCKERHUB_USER in $ENV_FILE}"
    _PASSWORD="${RIID_DOCKERHUB_TOKEN:-${RIID_DOCKERHUB_PASSWORD:-}}"
    : "${_PASSWORD:?Set RIID_DOCKERHUB_TOKEN (or RIID_DOCKERHUB_PASSWORD) in $ENV_FILE}"
    export _RIID_REG_USER="$RIID_DOCKERHUB_USER"
    export _RIID_REG_PASS="$_PASSWORD"
    export _RIID_SET_CREDENTIALS=1
    ;;
  selectel)
    : "${RIID_SELECTEL_USER:?Set RIID_SELECTEL_USER in $ENV_FILE}"
    _PASSWORD="${RIID_SELECTEL_TOKEN:-${RIID_SELECTEL_PASSWORD:-}}"
    : "${_PASSWORD:?Set RIID_SELECTEL_TOKEN (or RIID_SELECTEL_PASSWORD) in $ENV_FILE}"
    export _RIID_REG_USER="$RIID_SELECTEL_USER"
    export _RIID_REG_PASS="$_PASSWORD"
    export _RIID_SET_CREDENTIALS=1
    ;;
  local)
    export _RIID_SET_CREDENTIALS=0
    ;;
  *)
    echo "Unsupported profile '$PROFILE' (add case + credentials in $0, or use dockerhub|selectel|local)." >&2
    exit 1
    ;;
esac

TMP_INNER="$(mktemp)"
OUT="$(mktemp)"
trap 'rm -f "$TMP_INNER" "$OUT"' EXIT

export _RIID_PROFILE_YAML="$PROFILE_YAML"

# Base structure from ConfigMap; client.registries replaced from providers/registry/client/<profile>.yaml.
yq e '.data["config.yaml"]' "$CONFIGMAP_YAML" \
  | yq e '.client.registries = load(strenv(_RIID_PROFILE_YAML)).registries' - \
  > "$TMP_INNER"

if [[ "${_RIID_SET_CREDENTIALS}" == "1" ]]; then
  yq e '(.client.registries[].credentials) = {"username": strenv(_RIID_REG_USER), "password": strenv(_RIID_REG_PASS)}' -i "$TMP_INNER"
fi

kubectl create secret generic riid-config-secret \
  --from-file="config.yaml=$TMP_INNER" \
  -n riid-system \
  --dry-run=client -o yaml > "$OUT"

if [[ "$APPLY" -eq 1 ]]; then
  kubectl apply -f "$OUT"
  echo "Applied riid-config-secret (profile=$PROFILE). Restart: kubectl -n riid-system rollout restart daemonset riid" >&2
else
  cat "$OUT"
fi
