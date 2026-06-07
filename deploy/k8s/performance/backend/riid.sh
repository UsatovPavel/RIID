#!/usr/bin/env bash
set -euo pipefail

POD="${1:?POD is required}"
NS="${NS:?NS is required}"
CONTAINER="${CONTAINER:?CONTAINER is required}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:?IMAGE_REPOSITORY is required}"
IMAGE_REFERENCE="${IMAGE_REFERENCE:?IMAGE_REFERENCE is required}"
RUNTIME_ID="${RUNTIME_ID:-podman}"

kubectl -n "$NS" exec -c "$CONTAINER" "$POD" -- \
  curl --unix-socket /tmp/riid.sock --fail -sS -X POST "http://localhost/pull" \
  -H 'Content-Type: application/json' \
  -d "{\"repository\":\"${IMAGE_REPOSITORY}\",\"reference\":\"${IMAGE_REFERENCE}\",\"runtimeId\":\"${RUNTIME_ID}\"}" \
  >/dev/null
