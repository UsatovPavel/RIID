#!/usr/bin/env bash
set -euo pipefail

POD="${1:?POD is required}"
NS="${NS:?NS is required}"
CONTAINER="${CONTAINER:?CONTAINER is required}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:?IMAGE_REPOSITORY is required}"
IMAGE_REFERENCE="${IMAGE_REFERENCE:?IMAGE_REFERENCE is required}"
PODMAN_CLEAR_CACHE_BEFORE_PULL="${PODMAN_CLEAR_CACHE_BEFORE_PULL:-0}"

_pull_host="${PODMAN_REGISTRY_PULL_HOST:-}"
_bd="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_k8s="$(cd "$_bd/../.." && pwd)"
if [[ -z "$_pull_host" ]]; then
  _py="${_k8s}/providers/registry/image/resolve_smoke_repository.py"
  if [[ -f "$_py" ]]; then
    _pull_host="$(python3 "$_py" --k8s-root "$_k8s" --pull-host-hint 2>/dev/null || true)"
  fi
fi
if [[ -z "$_pull_host" ]]; then
  _cfg="${PERF_REGISTRY_ENV_FILE:-$_k8s/config/.env}"
  if [[ -f "$_cfg" ]]; then
    set -a
    # shellcheck source=/dev/null
    source "$_cfg"
    set +a
    if [[ -n "${REGISTRY_SELECTEL_ID:-}" ]]; then
      _pull_host="${REGISTRY_LOGIN_HOST:-cr.selcloud.ru}"
    fi
  fi
fi

# Podman pull: либо PODMAN_REGISTRY_PULL_HOST, либо подсказка из resolve_smoke_repository.py (config / FQ ref),
# либо legacy REGISTRY_LOGIN_HOST из deploy/k8s/config/.env.
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
