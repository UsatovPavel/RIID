#!/usr/bin/env bash
# docker-registry secret + default SA imagePullSecret for Selectel CR (mirrored Dragonfly/Bitnami images).
# Helm Selectel fragment deploy/k8s/.resolved/registry/helm/dragonfly-values-selectel.yaml sets
# global.imagePullSecrets so Redis/MySQL (their own ServiceAccounts) still pull cr.selcloud.ru images.
#
# Usage: setup-selectel-registry-pull-secret.sh [config/.env]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ENV_FILE="${1:-$K8S_DIR/config/.env}"

NAMESPACE="${DRAGONFLY_NAMESPACE:-dragonfly-system}"
SECRET_NAME="${DRAGONFLY_SELECTEL_PULL_SECRET_NAME:-dragonfly-selectel-registry}"
SERVICE_ACCOUNT="${DRAGONFLY_SERVICE_ACCOUNT:-default}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "setup-selectel-registry-pull-secret: missing env file (skip)." >&2
  exit 0
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

if [[ -z "${RIID_SELECTEL_USER:-}" ]]; then
  echo "setup-selectel-registry-pull-secret: RIID_SELECTEL_USER empty in $ENV_FILE (skip)." >&2
  exit 0
fi

SELECTEL_PASS="${RIID_SELECTEL_TOKEN:-${RIID_SELECTEL_PASSWORD:-}}"
if [[ -z "$SELECTEL_PASS" ]]; then
  echo "setup-selectel-registry-pull-secret: RIID_SELECTEL_TOKEN empty (skip)." >&2
  exit 0
fi

REG_HOST="${REGISTRY_LOGIN_HOST:-cr.selcloud.ru}"
DOCKER_SERVER="https://${REG_HOST}"

kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

kubectl -n "$NAMESPACE" create secret docker-registry "$SECRET_NAME" \
  --docker-server="$DOCKER_SERVER" \
  --docker-username="$RIID_SELECTEL_USER" \
  --docker-password="$SELECTEL_PASS" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

if kubectl -n "$NAMESPACE" get sa "$SERVICE_ACCOUNT" >/dev/null 2>&1; then
  NAMES="$(kubectl -n "$NAMESPACE" get sa "$SERVICE_ACCOUNT" -o jsonpath='{.imagePullSecrets[*].name}' 2>/dev/null | tr ' ' '\n')"
  if echo "$NAMES" | grep -Fxq "$SECRET_NAME" 2>/dev/null; then
    :
  else
    if [[ -z "$(kubectl -n "$NAMESPACE" get sa "$SERVICE_ACCOUNT" -o jsonpath='{.imagePullSecrets}' 2>/dev/null)" ]]; then
      kubectl -n "$NAMESPACE" patch sa "$SERVICE_ACCOUNT" --type='json' \
        -p="[{\"op\":\"add\",\"path\":\"/imagePullSecrets\",\"value\":[{\"name\":\"$SECRET_NAME\"}]}]" >/dev/null
    else
      kubectl -n "$NAMESPACE" patch sa "$SERVICE_ACCOUNT" --type='json' \
        -p="[{\"op\":\"add\",\"path\":\"/imagePullSecrets/-\",\"value\":{\"name\":\"$SECRET_NAME\"}}]" >/dev/null
    fi
  fi
fi

echo "setup-selectel-registry-pull-secret: ensured secret '$SECRET_NAME' for $REG_HOST." >&2
