#!/usr/bin/env bash
# Baseline arm: the engine pulls the image itself, without RIID and without the
# dfinit mirror. The engine is chosen with ENGINE (podman|containerd|porto), see
# backend/engine/.
#
# Called from scenario/run-pull-scenario.sh as BACKEND=bare with ENGINE=<engine>.
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

# Checked once per pod per run, marker in the pod's /tmp rather than a variable:
# run-pull-scenario.sh starts this script as a separate process for every image.
# Same shape as the dfinit arm's mirror check, and for the same reason — an arm
# that quietly measures something else costs a whole run.
MARKER="/tmp/.riid-bare-preflight-ok-${ENGINE}"
if ! kubectl -n "$NS" exec -c "$CONTAINER" "$POD" -- test -f "$MARKER" 2>/dev/null; then
  engine_preflight "$POD"
  # Only engines that share one config with the dfinit arm define this.
  if declare -F engine_no_mirror_check >/dev/null; then
    engine_no_mirror_check "$POD"
  fi
  kubectl -n "$NS" exec -c "$CONTAINER" "$POD" -- touch "$MARKER" >/dev/null
fi

if [[ "${CLEAR_CACHE_BEFORE_PULL:-0}" == "1" ]]; then
  engine_clear_cache "$POD"
fi

engine_pull "$POD" "$REF"
