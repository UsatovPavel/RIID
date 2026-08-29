#!/usr/bin/env bash
# Арм dfinit => <движок>: образ тянет сам движок, но через зеркало, которое
# dfinit прописал ему в конфиг, то есть трафик идёт в прокси dfdaemon и дальше
# в P2P. RIID в этом пути не участвует вовсе.
#
# Перед первым pull проверяется, что зеркало реально на месте. Без этой проверки
# арм тихо вырождается в обычный pull с лишним хопом и меряет накладные расходы
# вместо P2P — так уже был потерян один прогон. Отключается
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

# Проверка один раз на под за прогон: маркер в /tmp пода, а не переменная —
# run-pull-scenario.sh запускает бэкенд отдельным процессом на каждый образ.
if [[ "${DFINIT_SKIP_MIRROR_CHECK:-0}" != "1" ]]; then
  MARKER="/tmp/.riid-dfinit-mirror-ok-${ENGINE}"
  if ! kubectl -n "$NS" exec -c "$CONTAINER" "$POD" -- test -f "$MARKER" 2>/dev/null; then
    engine_mirror_check "$POD"
    kubectl -n "$NS" exec -c "$CONTAINER" "$POD" -- touch "$MARKER" >/dev/null
  fi
fi

if [[ "${CLEAR_CACHE_BEFORE_PULL:-${PODMAN_CLEAR_CACHE_BEFORE_PULL:-0}}" == "1" ]]; then
  engine_clear_cache "$POD"
fi

engine_pull_mirrored "$POD" "$REF"
