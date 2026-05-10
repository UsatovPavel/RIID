#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
ENV_FILE="${ENV_FILE:-$K8S_DIR/config/.env}"

NAMESPACE="${DRAGONFLY_NAMESPACE:-dragonfly-system}"
SECRET_NAME="${DRAGONFLY_DOCKERHUB_PULL_SECRET_NAME:-dragonfly-dockerhub}"
SERVICE_ACCOUNT="${DRAGONFLY_SERVICE_ACCOUNT:-default}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "setup-dockerhub-pull-secret: missing $ENV_FILE (skip)." >&2
  exit 0
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

if [[ -z "${RIID_DOCKERHUB_USER:-}" ]]; then
  echo "setup-dockerhub-pull-secret: RIID_DOCKERHUB_USER is empty in $ENV_FILE (skip)." >&2
  exit 0
fi

DOCKERHUB_PASSWORD="${RIID_DOCKERHUB_TOKEN:-${RIID_DOCKERHUB_PASSWORD:-}}"
if [[ -z "$DOCKERHUB_PASSWORD" ]]; then
  echo "setup-dockerhub-pull-secret: RIID_DOCKERHUB_TOKEN/RIID_DOCKERHUB_PASSWORD is empty in $ENV_FILE (skip)." >&2
  exit 0
fi

kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

kubectl -n "$NAMESPACE" create secret docker-registry "$SECRET_NAME" \
  --docker-server="https://index.docker.io/v1/" \
  --docker-username="$RIID_DOCKERHUB_USER" \
  --docker-password="$DOCKERHUB_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

if kubectl -n "$NAMESPACE" get sa "$SERVICE_ACCOUNT" >/dev/null 2>&1; then
  if kubectl -n "$NAMESPACE" get sa "$SERVICE_ACCOUNT" -o jsonpath='{.imagePullSecrets[*].name}' | tr ' ' '\n' | grep -Fxq "$SECRET_NAME" >/dev/null 2>&1; then
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

echo "setup-dockerhub-pull-secret: ensured secret '$SECRET_NAME' in namespace '$NAMESPACE'." >&2
