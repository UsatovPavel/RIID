#!/usr/bin/env bash
set -euo pipefail

POD="${1:?POD is required}"
NS="${NS:?NS is required}"
CONTAINER="${CONTAINER:?CONTAINER is required}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:?IMAGE_REPOSITORY is required}"
IMAGE_REFERENCE="${IMAGE_REFERENCE:?IMAGE_REFERENCE is required}"
PODMAN_CLEAR_CACHE_BEFORE_PULL="${PODMAN_CLEAR_CACHE_BEFORE_PULL:-0}"

if [[ "$PODMAN_CLEAR_CACHE_BEFORE_PULL" == "1" ]]; then
  kubectl -n "$NS" exec -c "$CONTAINER" "$POD" -- \
    podman system prune -af >/dev/null
fi

kubectl -n "$NS" exec -c "$CONTAINER" "$POD" -- \
  podman pull "${IMAGE_REPOSITORY}:${IMAGE_REFERENCE}" >/dev/null
