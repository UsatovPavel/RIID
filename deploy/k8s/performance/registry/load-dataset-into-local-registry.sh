#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

DATASET_FILE="${DATASET_FILE:-$K8S_DIR/performance/input/dataset_a.tsv}"
ENV_FILE="${PERF_REGISTRY_ENV_FILE:-$K8S_DIR/Selectel/.env}"
DEST_REGISTRY="${DEST_REGISTRY:-local-registry.registry-system.svc.cluster.local:5000}"
REGISTRY_NODE="${REGISTRY_NODE:-}"
LOADER_NAMESPACE="${REGISTRY_LOADER_NAMESPACE:-registry-system}"
LOADER_POD_NAME="${REGISTRY_LOADER_POD_NAME:-local-registry-loader}"
LOADER_IMAGE="${REGISTRY_LOADER_IMAGE:-ghcr.io/usatovpavel/riid:v0.3.8}"
KEEP_LOADER_POD="${KEEP_REGISTRY_LOADER_POD:-0}"

if [[ ! -f "$DATASET_FILE" ]]; then
  echo "load-dataset-into-local-registry: dataset not found: $DATASET_FILE" >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "load-dataset-into-local-registry: env file not found: $ENV_FILE" >&2
  echo "Set PERF_REGISTRY_ENV_FILE or create deploy/k8s/Selectel/.env" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${REGISTRY:?load-dataset-into-local-registry: REGISTRY is required in $ENV_FILE}"

REGISTRY="${REGISTRY%/}"
if [[ "$REGISTRY" == */* ]]; then
  SOURCE_REPO_PREFIX="$REGISTRY"
else
  SOURCE_REPO_PREFIX="${REGISTRY_LOGIN_HOST:-cr.selcloud.ru}/${REGISTRY}"
fi
SOURCE_REGISTRY_HOST="${SOURCE_REPO_PREFIX%%/*}"
SOURCE_REGISTRY_NAMESPACE="${SOURCE_REPO_PREFIX#*/}"

if [[ -z "$REGISTRY_NODE" ]]; then
  REGISTRY_NODE="$(kubectl get nodes -l riid.registry=true -o jsonpath='{.items[0].metadata.name}')"
fi
if [[ -z "$REGISTRY_NODE" ]]; then
  echo "load-dataset-into-local-registry: registry node label riid.registry=true not found" >&2
  echo "Run: make -C $K8S_DIR/Selectel mark-registry-node-auto" >&2
  exit 1
fi

kubectl create namespace "$LOADER_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

if [[ "$KEEP_LOADER_POD" != "1" ]]; then
  cleanup() {
    kubectl -n "$LOADER_NAMESPACE" delete pod "$LOADER_POD_NAME" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  }
  trap cleanup EXIT
fi

kubectl -n "$LOADER_NAMESPACE" delete pod "$LOADER_POD_NAME" --ignore-not-found >/dev/null 2>&1 || true
cat <<EOF | kubectl -n "$LOADER_NAMESPACE" apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: ${LOADER_POD_NAME}
  labels:
    app.kubernetes.io/name: local-registry-loader
spec:
  restartPolicy: Never
  nodeName: ${REGISTRY_NODE}
  containers:
    - name: loader
      image: ${LOADER_IMAGE}
      imagePullPolicy: IfNotPresent
      command: ["sh", "-lc", "sleep infinity"]
EOF
kubectl -n "$LOADER_NAMESPACE" wait --for=condition=Ready --timeout=180s "pod/${LOADER_POD_NAME}" >/dev/null

kubectl -n "$LOADER_NAMESPACE" exec "$LOADER_POD_NAME" -- sh -lc \
  'command -v podman >/dev/null || (export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && apt-get install -y --no-install-recommends podman fuse-overlayfs >/dev/null)'
kubectl -n "$LOADER_NAMESPACE" exec "$LOADER_POD_NAME" -- podman --version >/dev/null

if [[ -n "${REGISTRY_USER:-}" && -n "${REGISTRY_PASSWORD:-}" ]]; then
  kubectl -n "$LOADER_NAMESPACE" exec "$LOADER_POD_NAME" -- \
    env SRC_HOST="$SOURCE_REGISTRY_HOST" SRC_USER="$REGISTRY_USER" SRC_PASSWORD="$REGISTRY_PASSWORD" \
    sh -lc 'echo "$SRC_PASSWORD" | podman login "$SRC_HOST" --username "$SRC_USER" --password-stdin >/dev/null'
fi

is_fully_qualified_repo() {
  local repo="$1"
  local first="${repo%%/*}"
  if [[ "$first" == "localhost" || "$first" == *.* || "$first" == *:* ]]; then
    return 0
  fi
  return 1
}

is_already_prefixed_repo() {
  local repo="$1"
  [[ -n "$SOURCE_REGISTRY_NAMESPACE" ]] && [[ "$repo" == "${SOURCE_REGISTRY_NAMESPACE}/"* ]]
}

echo "load-dataset-into-local-registry: node=$REGISTRY_NODE pod=$LOADER_POD_NAME dataset=$DATASET_FILE" >&2
echo "load-dataset-into-local-registry: source_prefix=$SOURCE_REPO_PREFIX dest_registry=$DEST_REGISTRY" >&2

total=0
ok=0
failed=0

while IFS=$'\t' read -r repository reference _rest || [[ -n "${repository:-}" ]]; do
  [[ -z "${repository:-}" ]] && continue
  [[ "$repository" == "repository" ]] && continue
  [[ "$repository" =~ ^# ]] && continue

  reference="${reference:-latest}"
  source_repo="$repository"
  if ! is_fully_qualified_repo "$repository"; then
    if is_already_prefixed_repo "$repository"; then
      source_repo="${SOURCE_REGISTRY_HOST}/${repository}"
    else
      source_repo="${SOURCE_REPO_PREFIX}/${repository}"
    fi
  fi

  src="${source_repo}:${reference}"
  dst="${DEST_REGISTRY}/${repository}:${reference}"
  total=$((total + 1))

  echo "[$total] mirror $src -> $dst" >&2
  if ! kubectl -n "$LOADER_NAMESPACE" exec "$LOADER_POD_NAME" -- \
      env SRC="$src" DST="$dst" sh -lc 'set -e; podman pull "$SRC" >/dev/null; podman tag "$SRC" "$DST"; podman push --tls-verify=false "$DST" >/dev/null'; then
    failed=$((failed + 1))
    echo "[$total] FAILED: $src" >&2
    continue
  fi
  ok=$((ok + 1))
done < "$DATASET_FILE"

echo "load-dataset-into-local-registry: done total=$total ok=$ok failed=$failed" >&2
[[ "$failed" -eq 0 ]]
