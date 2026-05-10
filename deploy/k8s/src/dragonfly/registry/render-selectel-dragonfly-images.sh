#!/usr/bin/env bash
# Emit Helm values fragment: Docker Hub images rewritten to Selectel mirror under REG_PREFIX/worker/...
# (same layout as deploy/k8s/Selectel/registry/load-worker-node-image.sh).
#
# Usage: render-selectel-dragonfly-images.sh <path/to/bootstrap-or-selectel.env>
#
# Tags по умолчанию берутся из deploy/k8s/config/infra_versions.yaml (единый источник с install-dragonfly).
# Optional env:
#   INFRA_VERSIONS_FILE — путь к infra_versions.yaml
#   DRAGONFLY_OSS_IMAGE_TAG — legacy: один тег для manager+scheduler+client+seedClient
#   DRAGONFLY_SCHEDULER_IMAGE_TAG / DRAGONFLY_CLIENT_IMAGE_TAG — точечные переопределения после OSS
#   DRAGONFLY_BUSYBOX_TAG   — init busybox
#   DRAGONFLY_MYSQL_IMAGE_TAG (default 8.4.3-debian-12-r0)
#   DRAGONFLY_REDIS_IMAGE_TAG (default 7.2.5-debian-12-r0)
set -euo pipefail

ENV_FILE="${1:?usage: $0 <path/to/.env with REGISTRY>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
INFRA_VERSIONS_FILE="${INFRA_VERSIONS_FILE:-${K8S_DIR}/config/infra_versions.yaml}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "$0: file not found: $ENV_FILE" >&2
  exit 1
fi

if [[ ! -f "$INFRA_VERSIONS_FILE" ]]; then
  echo "$0: infra_versions not found: $INFRA_VERSIONS_FILE" >&2
  exit 1
fi

command -v yq >/dev/null 2>&1 || { echo "$0: yq is required" >&2; exit 1; }

tag_from_image_field() {
  local ref="$1"
  [[ -n "$ref" && "$ref" != "null" ]] || return 1
  local tag_part="${ref##*:}"
  [[ "$tag_part" != "$ref" ]] || return 1
  printf '%s\n' "$tag_part"
}

IMG_MGR="$(yq e '.dragonfly.images.manager.image' "$INFRA_VERSIONS_FILE")"
IMG_SCH="$(yq e '.dragonfly.images.scheduler.image' "$INFRA_VERSIONS_FILE")"
IMG_CLI="$(yq e '.dragonfly.images.client.image' "$INFRA_VERSIONS_FILE")"
IMG_BB="$(yq e '.storage.busybox.image' "$INFRA_VERSIONS_FILE")"

TAG_MGR="$(tag_from_image_field "$IMG_MGR")" || {
  echo "$0: bad .dragonfly.images.manager.image in $INFRA_VERSIONS_FILE" >&2
  exit 1
}
TAG_SCH="$(tag_from_image_field "$IMG_SCH")" || {
  echo "$0: bad .dragonfly.images.scheduler.image in $INFRA_VERSIONS_FILE" >&2
  exit 1
}
TAG_CLI="$(tag_from_image_field "$IMG_CLI")" || {
  echo "$0: bad .dragonfly.images.client.image in $INFRA_VERSIONS_FILE" >&2
  exit 1
}
TAG_BB="$(tag_from_image_field "$IMG_BB")" || {
  echo "$0: bad .storage.busybox.image in $INFRA_VERSIONS_FILE" >&2
  exit 1
}

if [[ -n "${DRAGONFLY_OSS_IMAGE_TAG:-}" ]]; then
  TAG_MGR="$DRAGONFLY_OSS_IMAGE_TAG"
  TAG_SCH="$DRAGONFLY_OSS_IMAGE_TAG"
  TAG_CLI="$DRAGONFLY_OSS_IMAGE_TAG"
fi
[[ -n "${DRAGONFLY_SCHEDULER_IMAGE_TAG:-}" ]] && TAG_SCH="$DRAGONFLY_SCHEDULER_IMAGE_TAG"
[[ -n "${DRAGONFLY_CLIENT_IMAGE_TAG:-}" ]] && TAG_CLI="$DRAGONFLY_CLIENT_IMAGE_TAG"
[[ -n "${DRAGONFLY_BUSYBOX_TAG:-}" ]] && TAG_BB="$DRAGONFLY_BUSYBOX_TAG"

TAG_MY="${DRAGONFLY_MYSQL_IMAGE_TAG:-8.4.3-debian-12-r0}"
TAG_RD="${DRAGONFLY_REDIS_IMAGE_TAG:-7.2.5-debian-12-r0}"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${REGISTRY:?set REGISTRY in $ENV_FILE}"

REGISTRY="${REGISTRY%/}"
if [[ "$REGISTRY" == */* ]]; then
  IMG_REGISTRY="${REGISTRY%%/*}"
  REG_PATH="${REGISTRY#*/}"
else
  IMG_REGISTRY="${REGISTRY_LOGIN_HOST:-cr.selcloud.ru}"
  REG_PATH="$REGISTRY"
fi

WP="${REG_PATH}/worker"

cat <<YAML
manager:
  image:
    registry: ${IMG_REGISTRY}
    repository: ${WP}/dragonflyoss/manager
    tag: ${TAG_MGR}
  initContainer:
    image:
      registry: ${IMG_REGISTRY}
      repository: ${WP}/library/busybox
      tag: ${TAG_BB}
scheduler:
  image:
    registry: ${IMG_REGISTRY}
    repository: ${WP}/dragonflyoss/scheduler
    tag: ${TAG_SCH}
  initContainer:
    image:
      registry: ${IMG_REGISTRY}
      repository: ${WP}/library/busybox
      tag: ${TAG_BB}
seedClient:
  image:
    registry: ${IMG_REGISTRY}
    repository: ${WP}/dragonflyoss/client
    tag: ${TAG_CLI}
  initContainer:
    image:
      registry: ${IMG_REGISTRY}
      repository: ${WP}/library/busybox
      tag: ${TAG_BB}
client:
  image:
    registry: ${IMG_REGISTRY}
    repository: ${WP}/dragonflyoss/client
    tag: ${TAG_CLI}
  initContainer:
    image:
      registry: ${IMG_REGISTRY}
      repository: ${WP}/library/busybox
      tag: ${TAG_BB}
mysql:
  image:
    registry: ${IMG_REGISTRY}
    repository: ${WP}/bitnamilegacy/mysql
    tag: ${TAG_MY}
redis:
  image:
    registry: ${IMG_REGISTRY}
    repository: ${WP}/bitnamilegacy/redis
    tag: ${TAG_RD}
YAML
