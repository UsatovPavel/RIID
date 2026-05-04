#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

DATASET_FILE="${DATASET_FILE:-$K8S_DIR/performance/input/dataset_a.tsv}"
ENV_FILE="${PERF_REGISTRY_ENV_FILE:-$K8S_DIR/Selectel/.env}"
DEST_REGISTRY="${DEST_REGISTRY:-local-registry.registry-system.svc.cluster.local:5000}"
REGISTRY_NODE="${REGISTRY_NODE:-}"
RIID_NAMESPACE="${RIID_NAMESPACE:-riid-system}"
RIID_LABEL="${RIID_LABEL:-app.kubernetes.io/name=riid}"

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

RIID_POD="$(kubectl -n "$RIID_NAMESPACE" get pods -l "$RIID_LABEL" --field-selector="spec.nodeName=${REGISTRY_NODE},status.phase=Running" -o jsonpath='{.items[0].metadata.name}')"
if [[ -z "$RIID_POD" ]]; then
  echo "load-dataset-into-local-registry: no running RIID pod on node $REGISTRY_NODE" >&2
  exit 1
fi

kubectl -n "$RIID_NAMESPACE" exec -c riid "$RIID_POD" -- podman --version >/dev/null

if [[ -n "${REGISTRY_USER:-}" && -n "${REGISTRY_PASSWORD:-}" ]]; then
  kubectl -n "$RIID_NAMESPACE" exec -c riid "$RIID_POD" -- \
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

echo "load-dataset-into-local-registry: node=$REGISTRY_NODE pod=$RIID_POD dataset=$DATASET_FILE" >&2
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
  if ! kubectl -n "$RIID_NAMESPACE" exec -c riid "$RIID_POD" -- \
      env SRC="$src" DST="$dst" sh -lc 'set -e; podman pull "$SRC" >/dev/null; podman tag "$SRC" "$DST"; podman push --tls-verify=false "$DST" >/dev/null'; then
    failed=$((failed + 1))
    echo "[$total] FAILED: $src" >&2
    continue
  fi
  ok=$((ok + 1))
done < "$DATASET_FILE"

echo "load-dataset-into-local-registry: done total=$total ok=$ok failed=$failed" >&2
[[ "$failed" -eq 0 ]]
