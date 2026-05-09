#!/usr/bin/env bash
# Emit Helm values fragment: Docker Hub images rewritten to Selectel mirror under REG_PREFIX/worker/...
# (same layout as deploy/k8s/Selectel/registry/load-worker-node-image.sh).
#
# Usage: render-selectel-dragonfly-images.sh <path/to/Selectel/.env>
#
# Optional env:
#   DRAGONFLY_OSS_IMAGE_TAG (default latest)
#   DRAGONFLY_BUSYBOX_TAG   (default latest)
#   DRAGONFLY_MYSQL_IMAGE_TAG (default 8.4.3-debian-12-r0)
#   DRAGONFLY_REDIS_IMAGE_TAG (default 7.2.5-debian-12-r0)
set -euo pipefail

ENV_FILE="${1:?usage: $0 <Selectel/.env>}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "$0: file not found: $ENV_FILE" >&2
  exit 1
fi

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
TAG_DF="${DRAGONFLY_OSS_IMAGE_TAG:-latest}"
TAG_BB="${DRAGONFLY_BUSYBOX_TAG:-latest}"
TAG_MY="${DRAGONFLY_MYSQL_IMAGE_TAG:-8.4.3-debian-12-r0}"
TAG_RD="${DRAGONFLY_REDIS_IMAGE_TAG:-7.2.5-debian-12-r0}"

cat <<YAML
manager:
  image:
    registry: ${IMG_REGISTRY}
    repository: ${WP}/dragonflyoss/manager
    tag: ${TAG_DF}
  initContainer:
    image:
      registry: ${IMG_REGISTRY}
      repository: ${WP}/library/busybox
      tag: ${TAG_BB}
scheduler:
  image:
    registry: ${IMG_REGISTRY}
    repository: ${WP}/dragonflyoss/scheduler
    tag: ${TAG_DF}
  initContainer:
    image:
      registry: ${IMG_REGISTRY}
      repository: ${WP}/library/busybox
      tag: ${TAG_BB}
seedClient:
  image:
    registry: ${IMG_REGISTRY}
    repository: ${WP}/dragonflyoss/client
    tag: ${TAG_DF}
  initContainer:
    image:
      registry: ${IMG_REGISTRY}
      repository: ${WP}/library/busybox
      tag: ${TAG_BB}
client:
  image:
    registry: ${IMG_REGISTRY}
    repository: ${WP}/dragonflyoss/client
    tag: ${TAG_DF}
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
