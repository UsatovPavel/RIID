#!/usr/bin/env bash
# Baseline-арм: движок тянет образ сам, без RIID и без зеркала dfinit.
# Движок выбирается через ENGINE (podman|containerd|porto), см. backend/engine/.
#
# Вызывается из scenario/run-pull-scenario.sh как BACKEND=bare с ENGINE=<движок>.
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

# PODMAN_CLEAR_CACHE_BEFORE_PULL — legacy-имя из backend/podman.sh, оставлено
# ради существующих вызовов performance/Makefile.
if [[ "${CLEAR_CACHE_BEFORE_PULL:-${PODMAN_CLEAR_CACHE_BEFORE_PULL:-0}}" == "1" ]]; then
  engine_clear_cache "$POD"
fi

engine_pull "$POD" "$REF"
