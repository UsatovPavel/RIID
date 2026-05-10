#!/usr/bin/env bash
# Зеркалирует образы в локальный registry для perf/smoke.
#
# Режимы:
#   По умолчанию — TSV DATASET_FILE + deploy/k8s/config/.env (REGISTRY_SELECTEL_ID → префикс источника).
#   LOAD_TEST_IMAGELIST=1 — список из resolve_smoke_repository.py (config/imagelist YAML);
#     опционально PERF_REGISTRY_ENV_FILE для тех же переменных;
#     префикс pull: TEST_REGISTRY_PULL_HOST, config test_registry_pull_host, или FQ ref в каталоге.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESOLVER_PY="${K8S_DIR}/providers/registry/image/resolve_smoke_repository.py"

DATASET_FILE="${DATASET_FILE:-$K8S_DIR/performance/input/dataset_a.tsv}"
ENV_FILE="${PERF_REGISTRY_ENV_FILE:-$K8S_DIR/config/.env}"
LOAD_TEST_IMAGELIST="${LOAD_TEST_IMAGELIST:-0}"
DEST_REGISTRY="${DEST_REGISTRY:-local-registry.registry-system.svc.cluster.local:5000}"
REGISTRY_NODE="${REGISTRY_NODE:-}"
LOADER_NAMESPACE="${REGISTRY_LOADER_NAMESPACE:-registry-system}"
LOADER_POD_NAME="${REGISTRY_LOADER_POD_NAME:-local-registry-loader}"
LOADER_IMAGE="${REGISTRY_LOADER_IMAGE:-ghcr.io/usatovpavel/riid:v0.3.8}"
KEEP_LOADER_POD="${KEEP_REGISTRY_LOADER_POD:-0}"

if [[ "$LOAD_TEST_IMAGELIST" == 1 ]]; then
  if [[ ! -f "$RESOLVER_PY" ]]; then
    echo "load-dataset-into-local-registry: missing resolver $RESOLVER_PY" >&2
    exit 1
  fi
  if [[ -n "${PERF_REGISTRY_ENV_FILE:-}" && -f "${PERF_REGISTRY_ENV_FILE}" ]]; then
    ENV_FILE="${PERF_REGISTRY_ENV_FILE}"
  elif [[ -f "$ENV_FILE" ]]; then
    :
  else
    ENV_FILE=""
  fi
  if [[ -n "$ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
  fi
else
  if [[ ! -f "$DATASET_FILE" ]]; then
    echo "load-dataset-into-local-registry: dataset not found: $DATASET_FILE" >&2
    exit 1
  fi

  if [[ ! -f "$ENV_FILE" ]]; then
    echo "load-dataset-into-local-registry: env file not found: $ENV_FILE" >&2
    echo "Set PERF_REGISTRY_ENV_FILE or create deploy/k8s/config/.env (see config/.env.example)" >&2
    exit 1
  fi

  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a

  # shellcheck source=../../bootstrap/registry/registry-prefix-from-env.inc.sh
  source "$K8S_DIR/bootstrap/registry/registry-prefix-from-env.inc.sh"
  riid_registry_prefix_from_env

  SOURCE_REPO_PREFIX="$REG_PREFIX"
  SOURCE_REGISTRY_HOST="$REG_HOST"
  SOURCE_REGISTRY_NAMESPACE="$REG_REPO_PREFIX"
fi

LOGIN_HOST="${SOURCE_REGISTRY_HOST:-}"
if [[ "$LOAD_TEST_IMAGELIST" == 1 && -z "$LOGIN_HOST" ]]; then
  LOGIN_HOST="$(python3 "$RESOLVER_PY" --k8s-root "$K8S_DIR" --pull-host-hint 2>/dev/null || true)"
fi
if [[ "$LOAD_TEST_IMAGELIST" == 1 && -z "$LOGIN_HOST" ]]; then
  LOGIN_HOST="${REGISTRY_LOGIN_HOST:-}"
fi

if [[ -z "$REGISTRY_NODE" ]]; then
  REGISTRY_NODE="$(kubectl get nodes -l riid.registry=true -o jsonpath='{.items[0].metadata.name}')"
fi
if [[ -z "$REGISTRY_NODE" ]]; then
  echo "load-dataset-into-local-registry: registry node label riid.registry=true not found" >&2
  echo "Run: make -C $K8S_DIR/bootstrap mark-registry-node-auto" >&2
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

if [[ -n "${RIID_SELECTEL_USER:-}" ]]; then
  _cr_pass="${RIID_SELECTEL_TOKEN:-${RIID_SELECTEL_PASSWORD:-}}"
  if [[ -n "$_cr_pass" && -n "${LOGIN_HOST:-}" ]]; then
    kubectl -n "$LOADER_NAMESPACE" exec "$LOADER_POD_NAME" -- \
      env SRC_HOST="$LOGIN_HOST" SRC_USER="$RIID_SELECTEL_USER" SRC_PASSWORD="$_cr_pass" \
      sh -lc 'echo "$SRC_PASSWORD" | podman login "$SRC_HOST" --username "$SRC_USER" --password-stdin >/dev/null'
  fi
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
echo "load-dataset-into-local-registry: source_prefix=${SOURCE_REPO_PREFIX:-} dest_registry=$DEST_REGISTRY" >&2

total=0
ok=0
failed=0

if [[ "$LOAD_TEST_IMAGELIST" == 1 ]]; then
  echo "load-dataset-into-local-registry: mode=catalog resolve_smoke_repository.py" >&2
fi

_catalog_pull_push() {
  local src="$1" dst="$2"
  total=$((total + 1))
  echo "[$total] mirror $src -> $dst" >&2
  if ! kubectl -n "$LOADER_NAMESPACE" exec "$LOADER_POD_NAME" -- \
      env SRC="$src" DST="$dst" sh -lc 'set -e; podman pull "$SRC" >/dev/null; podman tag "$SRC" "$DST"; podman push --tls-verify=false "$DST" >/dev/null'; then
    failed=$((failed + 1))
    echo "[$total] FAILED: $src" >&2
    return 1
  fi
  ok=$((ok + 1))
}

if [[ "$LOAD_TEST_IMAGELIST" == 1 ]]; then
  while IFS=$'\t' read -r pull_ref repository reference _rest || [[ -n "${pull_ref:-}" ]]; do
    [[ -z "${pull_ref:-}" ]] && continue
    [[ "$pull_ref" =~ ^# ]] && continue
    local_dst=""
    if [[ "$reference" == sha256:* ]]; then
      local_dst="${DEST_REGISTRY}/${repository}@${reference}"
    else
      local_dst="${DEST_REGISTRY}/${repository}:${reference}"
    fi
    _catalog_pull_push "$pull_ref" "$local_dst" || true
  done < <(python3 "$RESOLVER_PY" --k8s-root "$K8S_DIR" --emit-catalog-load-lines)
else
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
    _catalog_pull_push "$src" "$dst" || true
  done < "$DATASET_FILE"
fi

echo "load-dataset-into-local-registry: done total=$total ok=$ok failed=$failed" >&2
[[ "$failed" -eq 0 ]]
