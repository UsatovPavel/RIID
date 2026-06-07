#!/usr/bin/env bash
# Удаляет из локального registry v2 образы, перечисленные в TSV (колонки repository, tag),
# в том же формате, что dataset_a.tsv / dataset_b.tsv.
#
# Использует только curl + Registry HTTP API v2 (GET манифеста → Docker-Content-Digest → DELETE).
#
# Переменные окружения:
#   DATASET_FILE — путь к TSV (default: .../performance/input/dataset_a.tsv)
#   REGISTRY_URL — базовый URL без завершающего слэша
#       default: http://local-registry.registry-system.svc.cluster.local:5000
#       С ноутбука: kubectl port-forward -n registry-system svc/local-registry 5000:5000
#       затем REGISTRY_URL=http://127.0.0.1:5000
#   DELETE_DATASET_DRY_RUN=1 — только печать действий, без DELETE
#   DELETE_DATASET_RUN_GC=1 — после удалений выполнить garbage-collect в поде local-registry
#       (освобождает blob-мусор; нужен kubectl и деплой local-registry)
#   DELETE_DATASET_DEBUG=1 — при ошибке DELETE печатать HTTP-код и тело ответа
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

DATASET_FILE="${DATASET_FILE:-$K8S_DIR/performance/input/dataset_a.tsv}"
REGISTRY_URL="${REGISTRY_URL:-http://local-registry.registry-system.svc.cluster.local:5000}"
REGISTRY_URL="${REGISTRY_URL%/}"
DELETE_DATASET_DRY_RUN="${DELETE_DATASET_DRY_RUN:-0}"
DELETE_DATASET_RUN_GC="${DELETE_DATASET_RUN_GC:-0}"
DELETE_DATASET_DEBUG="${DELETE_DATASET_DEBUG:-0}"

LOADER_NAMESPACE="${REGISTRY_LOADER_NAMESPACE:-registry-system}"
GC_DEPLOYMENT="${LOCAL_REGISTRY_DEPLOYMENT:-local-registry}"

# По очереди: list/index обычно нужны для multi-arch; затем обычный manifest.
ACCEPT_TRY_ORDER=(
  "application/vnd.docker.distribution.manifest.list.v2+json"
  "application/vnd.docker.distribution.manifest.v2+json"
  "application/vnd.oci.image.index.v1+json"
  "application/vnd.oci.image.manifest.v1+json"
)

if [[ ! -f "$DATASET_FILE" ]]; then
  echo "delete-dataset-from-local-registry: dataset not found: $DATASET_FILE" >&2
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "delete-dataset-from-local-registry: curl is required" >&2
  exit 1
fi

# Печатает digest<TAB>accept — для DELETE нужен тот же Accept, что и при успешном GET манифеста.
manifest_digest_and_accept() {
  local repo="$1"
  local ref="$2"
  local url="${REGISTRY_URL}/v2/${repo}/manifests/${ref}"
  local hdr digest ac
  hdr="$(mktemp)"
  # shellcheck disable=SC2064
  trap "rm -f '$hdr'" RETURN
  for ac in "${ACCEPT_TRY_ORDER[@]}"; do
    if curl -fsS -D "$hdr" -o /dev/null -H "Accept: $ac" "$url" 2>/dev/null; then
      digest="$(tr -d '\r' <"$hdr" | grep -i '^docker-content-digest:' | awk '{print $2}' | head -1)"
      if [[ -n "$digest" ]]; then
        printf '%s\t%s\n' "$digest" "$ac"
        return 0
      fi
    fi
  done
  return 1
}

delete_manifest_by_digest() {
  local repo="$1"
  local digest="$2"
  local accept_used="$3"
  local url="${REGISTRY_URL}/v2/${repo}/manifests/${digest}"
  local body code ac

  body="$(mktemp)"
  # shellcheck disable=SC2064
  trap "rm -f '$body'" RETURN

  try_delete() {
    local ac="$1"
    code="$(curl -sS -o "$body" -w '%{http_code}' -X DELETE -H "Accept: $ac" "$url")" || code="000"
    [[ "$code" == "202" || "$code" == "200" ]]
  }

  if [[ -n "$accept_used" ]] && try_delete "$accept_used"; then
    return 0
  fi
  if [[ -n "$accept_used" && "$DELETE_DATASET_DEBUG" == "1" ]]; then
    echo "delete-dataset-from-local-registry: DELETE primary failed http=${code:-} accept=${accept_used}" >&2
    tr -d '\r' <"$body" | head -c 400 >&2 || true
    echo >&2
  fi

  for ac in "${ACCEPT_TRY_ORDER[@]}"; do
    [[ "$ac" == "$accept_used" ]] && continue
    if try_delete "$ac"; then
      return 0
    fi
  done

  if code="$(curl -sS -o "$body" -w '%{http_code}' -X DELETE "$url")" && [[ "$code" == "202" || "$code" == "200" ]]; then
    return 0
  fi

  echo "delete-dataset-from-local-registry: DELETE failed repo=${repo} digest=${digest} http=${code:-}" >&2
  tr -d '\r' <"$body" | head -c 500 >&2 || true
  echo >&2
  return 1
}

run_gc_in_cluster() {
  if ! command -v kubectl >/dev/null 2>&1; then
    echo "delete-dataset-from-local-registry: kubectl not found, skip GC" >&2
    return 0
  fi
  local pod
  pod="$(kubectl -n "$LOADER_NAMESPACE" get pods -l app.kubernetes.io/name="$GC_DEPLOYMENT" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  if [[ -z "$pod" ]]; then
    echo "delete-dataset-from-local-registry: no pod for $GC_DEPLOYMENT in $LOADER_NAMESPACE, skip GC" >&2
    return 0
  fi
  echo "delete-dataset-from-local-registry: garbage-collect pod=$pod (blobs)" >&2
  # registry:2 — бинарь /bin/registry или registry в PATH
  if kubectl -n "$LOADER_NAMESPACE" exec "$pod" -c registry -- \
    /bin/registry garbage-collect /etc/docker/registry/config.yml; then
    return 0
  fi
  kubectl -n "$LOADER_NAMESPACE" exec "$pod" -c registry -- \
    registry garbage-collect /etc/docker/registry/config.yml || true
}

echo "delete-dataset-from-local-registry: registry=$REGISTRY_URL dataset=$DATASET_FILE dry_run=$DELETE_DATASET_DRY_RUN" >&2

total=0
ok=0
missing=0
fail=0

while IFS=$'\t' read -r repository reference _rest || [[ -n "${repository:-}" ]]; do
  [[ -z "${repository:-}" ]] && continue
  [[ "$repository" == "repository" ]] && continue
  [[ "$repository" =~ ^# ]] && continue

  reference="${reference:-latest}"
  total=$((total + 1))

  line=""
  digest=""
  accept_used=""
  if ! line="$(manifest_digest_and_accept "$repository" "$reference")"; then
    echo "[$total] SKIP (no manifest): ${repository}:${reference}" >&2
    missing=$((missing + 1))
    continue
  fi
  digest="${line%%$'\t'*}"
  accept_used="${line#*$'\t'}"

  echo "[$total] DELETE ${repository}:${reference} digest=$digest accept=${accept_used}" >&2
  if [[ "$DELETE_DATASET_DRY_RUN" == "1" ]]; then
    ok=$((ok + 1))
    continue
  fi
  if delete_manifest_by_digest "$repository" "$digest" "$accept_used"; then
    ok=$((ok + 1))
  else
    echo "[$total] FAILED: ${repository}:${reference}" >&2
    fail=$((fail + 1))
  fi
done <"$DATASET_FILE"

echo "delete-dataset-from-local-registry: done total=$total deleted_ok=$ok missing=$missing failed=$fail" >&2

if [[ "$DELETE_DATASET_DRY_RUN" != "1" && "$DELETE_DATASET_RUN_GC" == "1" ]]; then
  run_gc_in_cluster || true
fi

[[ "$fail" -eq 0 ]]
