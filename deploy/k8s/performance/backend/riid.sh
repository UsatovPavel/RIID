#!/usr/bin/env bash
set -euo pipefail

POD="${1:?POD is required}"
NS="${NS:?NS is required}"
CONTAINER="${CONTAINER:?CONTAINER is required}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:?IMAGE_REPOSITORY is required}"
IMAGE_REFERENCE="${IMAGE_REFERENCE:?IMAGE_REFERENCE is required}"
# The daemon's runtimeId and the driver name in backend/engine/ are the same
# vocabulary (podman | containerd | porto), so the arm needs one knob, not two.
ENGINE="${ENGINE:-podman}"

BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=engine/common.inc.sh
source "$BACKEND_DIR/engine/common.inc.sh"

# Through riid_kubectl, not bare kubectl: a dropped exec loses the pull with the
# request never reaching the daemon - RIID logs nothing for that image at all,
# which is how one pod in ten failed on a different image in each of two runs.
# Retried only on connection-class errors, where nothing ran and nothing is
# masked; an HTTP error from the daemon still fails the pull, as it must.
riid_kubectl -n "$NS" exec -c "$CONTAINER" "$POD" -- \
  curl --unix-socket /tmp/riid.sock --fail -sS -X POST "http://localhost/pull" \
  -H 'Content-Type: application/json' \
  -d "{\"repository\":\"${IMAGE_REPOSITORY}\",\"reference\":\"${IMAGE_REFERENCE}\",\"runtimeId\":\"${ENGINE}\"}" \
  >/dev/null
