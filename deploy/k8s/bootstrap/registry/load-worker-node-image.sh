#!/usr/bin/env bash
# Mirrors worker-related images to private registry.
# Default scope: RIID runtime image + Dragonfly stack images used on cluster nodes.
#
# Usage:
#   ./load-worker-node-image.sh [deploy/k8s/config/.env] [optional-second-.env]
#
# Переменные — см. deploy/k8s/config/.env.example (REGISTRY_SELECTEL_ID, RIID_*).
#
# Optional:
#   REGISTRY_LOGIN_HOST
#   WORKER_IMAGE_LIST   # space-separated full image refs to mirror
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DIR/../../../.." && pwd)"
_DEFAULT_ENV="$REPO_ROOT/deploy/k8s/config/.env"
ENV_FILE="${1:-$_DEFAULT_ENV}"
EXTRA_ENV_FILE="${2:-}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "load-worker-node-image: missing env file: $ENV_FILE (copy deploy/k8s/config/.env.example)" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
if [[ -n "$EXTRA_ENV_FILE" && "$EXTRA_ENV_FILE" != "$ENV_FILE" && -f "$EXTRA_ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$EXTRA_ENV_FILE"
fi
set +a

# shellcheck source=registry-prefix-from-env.inc.sh
source "$DIR/registry-prefix-from-env.inc.sh"
riid_registry_prefix_from_env

: "${RIID_DOCKERHUB_USER:?set RIID_DOCKERHUB_USER in $ENV_FILE}"
DOCKERHUB_TOKEN="${RIID_DOCKERHUB_TOKEN:-${RIID_DOCKERHUB_PASSWORD:-}}"
: "${DOCKERHUB_TOKEN:?set RIID_DOCKERHUB_TOKEN (or RIID_DOCKERHUB_PASSWORD) in $ENV_FILE}"
: "${RIID_SELECTEL_USER:?set RIID_SELECTEL_USER in $ENV_FILE}"
SELECTEL_TOKEN="${RIID_SELECTEL_TOKEN:-${RIID_SELECTEL_PASSWORD:-}}"
: "${SELECTEL_TOKEN:?set RIID_SELECTEL_TOKEN (or RIID_SELECTEL_PASSWORD) in $ENV_FILE}"

if [[ -n "${WORKER_IMAGE_LIST:-}" ]]; then
  read -r -a IMAGES <<<"$WORKER_IMAGE_LIST"
else
  IMAGES=(
    "ghcr.io/usatovpavel/riid:v0.3.8"
    "docker.io/curlimages/curl:latest"
    "docker.io/library/ubuntu:24.04"
    "docker.io/dragonflyoss/manager:latest"
    "docker.io/dragonflyoss/scheduler:latest"
    "docker.io/dragonflyoss/client:latest"
    "docker.io/busybox:latest"
    "docker.io/bitnamilegacy/mysql:8.4.3-debian-12-r0"
    "docker.io/bitnamilegacy/redis:7.2.5-debian-12-r0"
  )
fi

docker_login_once() {
  local host="$1" user="$2" pass="$3"
  echo "$pass" | docker login "$host" --username "$user" --password-stdin >/dev/null
}

echo ">>> docker login docker.io (pull sources)"
docker_login_once "docker.io" "$RIID_DOCKERHUB_USER" "$DOCKERHUB_TOKEN"

echo ">>> docker login $REG_HOST (push targets)"
docker_login_once "$REG_HOST" "$RIID_SELECTEL_USER" "$SELECTEL_TOKEN"

strip_known_registry_host() {
  local r="$1"
  r="${r#docker.io/}"
  r="${r#ghcr.io/}"
  r="${r#quay.io/}"
  printf '%s' "$r"
}

push_repo_for_selectel() {
  local repo="$1"
  local strip="${2:-0}"
  if [[ "$strip" == 1 ]] && [[ "$repo" == library/* ]]; then
    echo "${repo#library/}"
  else
    echo "$repo"
  fi
}

_strip_push="${REGISTRY_PUSH_REPO_STRIP_LIBRARY:-}"
if [[ -z "$_strip_push" ]]; then
  if [[ "$REG_HOST" == *selcloud.ru ]]; then
    _strip_push=1
  else
    _strip_push=0
  fi
fi

for src in "${IMAGES[@]}"; do
  [[ -z "$src" ]] && continue
  img="$(strip_known_registry_host "$src")"
  if [[ "$img" == *:* ]]; then
    tag="${img##*:}"
    repo="${img%:*}"
  else
    tag="latest"
    repo="$img"
  fi
  push_repo="$(push_repo_for_selectel "$repo" "$_strip_push")"
  dst="${REG_PREFIX}/${push_repo}:${tag}"
  echo ">>> mirror $src -> $dst" >&2
  if ! docker pull "$src" >/dev/null 2>&1; then
    echo "FAIL pull: $src" >&2
    exit 1
  fi
  docker tag "$src" "$dst" || {
    echo "FAIL tag: $src -> $dst" >&2
    exit 1
  }
  docker push "$dst" >/dev/null
done

echo "load-worker-node-image: done (${#IMAGES[@]} refs)" >&2
