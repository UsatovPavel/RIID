#!/usr/bin/env bash
set -euo pipefail

POD="${1:?POD is required}"
NS="${NS:?NS is required}"
CONTAINER="${CONTAINER:?CONTAINER is required}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:?IMAGE_REPOSITORY is required}"
IMAGE_REFERENCE="${IMAGE_REFERENCE:?IMAGE_REFERENCE is required}"
PODMAN_CLEAR_CACHE_BEFORE_PULL="${PODMAN_CLEAR_CACHE_BEFORE_PULL:-0}"

_pull_host="${PODMAN_REGISTRY_PULL_HOST:-}"
if [[ -z "$_pull_host" ]]; then
  _bd="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  _k8s="$(cd "$_bd/../.." && pwd)"
  _selectel_env="${PERF_REGISTRY_ENV_FILE:-$_k8s/Selectel/.env}"
  if [[ -f "$_selectel_env" ]]; then
    set -a
    # shellcheck source=/dev/null
    source "$_selectel_env"
    set +a
    if [[ -n "${REGISTRY:-}" ]]; then
      _pull_host="${REGISTRY_LOGIN_HOST:-cr.selcloud.ru}"
    fi
  fi
fi

# После build-datasets.sh: repository = <REG_REPO_PREFIX>/<push_repo> для RIID /pull.
# Для podman pull — суффикс хоста публичного registry (совпадает с REGISTRY_LOGIN_HOST в Selectel/.env при зеркале).
if [[ -n "$_pull_host" ]]; then
  PULL_REF="${_pull_host%/}/${IMAGE_REPOSITORY}:${IMAGE_REFERENCE}"
else
  PULL_REF="${IMAGE_REPOSITORY}:${IMAGE_REFERENCE}"
fi

PODMAN_TLS_VERIFY="${PODMAN_TLS_VERIFY:-}"
if [[ -z "$PODMAN_TLS_VERIFY" ]]; then
  if [[ "${PODMAN_REGISTRY_PULL_HOST:-}" == "local-registry.registry-system.svc.cluster.local:5000" ]]; then
    PODMAN_TLS_VERIFY=false
  else
    PODMAN_TLS_VERIFY=true
  fi
fi

if [[ "$PODMAN_CLEAR_CACHE_BEFORE_PULL" == "1" ]]; then
  kubectl -n "$NS" exec -c "$CONTAINER" "$POD" -- \
    podman system prune -af >/dev/null
fi

kubectl -n "$NS" exec -c "$CONTAINER" "$POD" -- \
  podman pull --tls-verify="$PODMAN_TLS_VERIFY" "$PULL_REF" >/dev/null
