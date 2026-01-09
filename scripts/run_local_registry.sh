#!/usr/bin/env bash
set -euo pipefail

REG_NAME=${REG_NAME:-reg}
REG_PORT=${REG_PORT:-5000}
IMAGE=${IMAGE:-hello-world}
LOCAL_IMAGE=localhost:${REG_PORT}/${IMAGE}

if ! command -v docker >/dev/null 2>&1; then
  echo "docker not found in PATH" >&2
  exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -q "^${REG_NAME}\$"; then
  echo "Starting registry:2 as ${REG_NAME} on port ${REG_PORT}..."
  docker run -d -p ${REG_PORT}:5000 --name ${REG_NAME} registry:2 >/dev/null
else
  echo "Registry container ${REG_NAME} already running."
fi

echo "Pulling ${IMAGE}..."
docker pull ${IMAGE} >/dev/null
echo "Tagging and pushing ${LOCAL_IMAGE}..."
docker tag ${IMAGE} ${LOCAL_IMAGE}
docker push ${LOCAL_IMAGE} >/dev/null

echo "Local registry ready at http://localhost:${REG_PORT}, image: ${LOCAL_IMAGE}"

