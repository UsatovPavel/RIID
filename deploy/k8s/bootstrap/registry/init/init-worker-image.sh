#!/usr/bin/env bash
# Build and push worker tooling image (Podman stack + diagnostics; no RIID JAR here).
#
# Matches bootstrap/registry notes: podman/fuse-overlayfs + storage.conf,
# curl/jq/iproute2/netcat, registries scaffold for HTTP local-registry + HTTPS Selectel
# host (no credentials in-layer).
#
# Usage:
#   ./init-worker-image.sh [deploy/k8s/config/.env] [optional-second-.env]
#
# Required in config .env (see deploy/k8s/config/.env.example):
#   REGISTRY_SELECTEL_NAME
#   RIID_DOCKERHUB_USER
#   RIID_DOCKERHUB_TOKEN or RIID_DOCKERHUB_PASSWORD
#   RIID_SELECTEL_USER
#   RIID_SELECTEL_TOKEN or RIID_SELECTEL_PASSWORD
#
# Optional env (export before run):
#   REGISTRY_LOGIN_HOST          (default cr.selcloud.ru when ID без префикса host)
#   WORKER_TOOLING_IMAGE_REF     Override full pushed ref (default REG_PREFIX/worker/k8s-worker-tooling:24.04)
#   WORKER_LOCAL_REGISTRY_HOST   (--build-arg, default Kubernetes local-registry Service DNS:5000)
#   BUILD_ENGINE                 docker | podman (default: docker)
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DIR/../../../../../.." && pwd)"
_DEFAULT_ENV="$REPO_ROOT/deploy/k8s/config/.env"
ENV_FILE="${1:-$_DEFAULT_ENV}"
EXTRA_ENV_FILE="${2:-}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "init-worker-image: missing env file: $ENV_FILE (copy deploy/k8s/config/.env.example)" >&2
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

# shellcheck source=../registry-prefix-from-env.inc.sh
source "$DIR/../registry-prefix-from-env.inc.sh"
riid_registry_prefix_from_env

: "${RIID_DOCKERHUB_USER:?set RIID_DOCKERHUB_USER in $ENV_FILE}"
DOCKERHUB_TOKEN="${RIID_DOCKERHUB_TOKEN:-${RIID_DOCKERHUB_PASSWORD:-}}"
: "${DOCKERHUB_TOKEN:?set RIID_DOCKERHUB_TOKEN (or RIID_DOCKERHUB_PASSWORD) in $ENV_FILE}"
: "${RIID_SELECTEL_USER:?set RIID_SELECTEL_USER in $ENV_FILE}"
SELECTEL_TOKEN="${RIID_SELECTEL_TOKEN:-${RIID_SELECTEL_PASSWORD:-}}"
: "${SELECTEL_TOKEN:?set RIID_SELECTEL_TOKEN (or RIID_SELECTEL_PASSWORD) in $ENV_FILE}"

SELECTEL_REGISTRY_HOST="${WORKER_SELECTEL_REGISTRY_HOST:-$REG_HOST}"
LOCAL_REGISTRY_HOST="${WORKER_LOCAL_REGISTRY_HOST:-local-registry.registry-system.svc.cluster.local:5000}"
DEST_REF="${WORKER_TOOLING_IMAGE_REF:-${REG_PREFIX}/worker/k8s-worker-tooling:24.04}"

ENGINE="${BUILD_ENGINE:-docker}"
if ! command -v "$ENGINE" >/dev/null 2>&1; then
  echo "init-worker-image: $ENGINE not found in PATH" >&2
  exit 1
fi

docker_login_once() {
  local host="$1" user="$2" pass="$3"
  echo "$pass" | "$ENGINE" login "$host" --username "$user" --password-stdin >/dev/null
}

echo ">>> $ENGINE login docker.io (pull base ubuntu:24.04)"
docker_login_once "docker.io" "$RIID_DOCKERHUB_USER" "$DOCKERHUB_TOKEN"

echo ">>> $ENGINE login $REG_HOST (push tooling image)"
docker_login_once "$REG_HOST" "$RIID_SELECTEL_USER" "$SELECTEL_TOKEN"

DOCKERFILE="$DIR/Dockerfile.worker"
echo ">>> $ENGINE build $DEST_REF (LOCAL_REGISTRY_HOST=$LOCAL_REGISTRY_HOST SELECTEL_REGISTRY_HOST=$SELECTEL_REGISTRY_HOST)"

"$ENGINE" build \
  --pull \
  -f "$DOCKERFILE" \
  --build-arg "LOCAL_REGISTRY_HOST=$LOCAL_REGISTRY_HOST" \
  --build-arg "SELECTEL_REGISTRY_HOST=$SELECTEL_REGISTRY_HOST" \
  -t "$DEST_REF" \
  "$REPO_ROOT"

echo ">>> $ENGINE push $DEST_REF"
"$ENGINE" push "$DEST_REF"

echo "init-worker-image: pushed $DEST_REF"
