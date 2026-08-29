#!/usr/bin/env bash
# The dfinit => <engine> arm: the engine pulls the image itself, but through the
# mirror dfinit wrote into its config, so the traffic goes to the dfdaemon proxy
# and on into P2P. RIID takes no part in this path at all.
#
# Before the first pull that mirror entry — the registry mirror dfinit wrote into
# the engine's own config, pointing at the dfdaemon proxy — is verified to be in
# place. Without that check
# the arm silently degrades into a plain pull with an extra hop and measures
# overhead instead of P2P — one run was already lost that way. Turn it off with
# DFINIT_SKIP_MIRROR_CHECK=1.
set -euo pipefail

POD="${1:?POD is required}"
NS="${NS:?NS is required}"
CONTAINER="${CONTAINER:?CONTAINER is required}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:?IMAGE_REPOSITORY is required}"
IMAGE_REFERENCE="${IMAGE_REFERENCE:?IMAGE_REFERENCE is required}"
ENGINE="${ENGINE:-podman}"

BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RIID_K8S_ROOT="$(cd "$BACKEND_DIR/../.." && pwd)"
export RIID_K8S_ROOT

DRIVER="$BACKEND_DIR/engine/${ENGINE}.inc.sh"
if [[ ! -f "$DRIVER" ]]; then
  echo "Unsupported ENGINE=$ENGINE (no $DRIVER)" >&2
  exit 2
fi

# shellcheck source=engine/common.inc.sh
source "$BACKEND_DIR/engine/common.inc.sh"
# shellcheck source=/dev/null
source "$DRIVER"

REF="$(engine_ref "$IMAGE_REPOSITORY" "$IMAGE_REFERENCE")"

# Checked once per pod per run: a marker in the pod's /tmp rather than a
# variable, because run-pull-scenario.sh starts the backend as a separate
# process for every image.
if [[ "${DFINIT_SKIP_MIRROR_CHECK:-0}" != "1" ]]; then
  MARKER="/tmp/.riid-dfinit-mirror-ok-${ENGINE}"
  if ! kubectl -n "$NS" exec -c "$CONTAINER" "$POD" -- test -f "$MARKER" 2>/dev/null; then
    engine_preflight "$POD"
    engine_mirror_check "$POD"
    kubectl -n "$NS" exec -c "$CONTAINER" "$POD" -- touch "$MARKER" >/dev/null
  fi
fi

if [[ "${CLEAR_CACHE_BEFORE_PULL:-0}" == "1" ]]; then
  engine_clear_cache "$POD"
fi

engine_pull_mirrored "$POD" "$REF"
