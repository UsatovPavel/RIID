#!/usr/bin/env bash
# POST /pull через Unix-сокет RIID во всех подах DaemonSet (по одному запросу на pod на ноде).
# Нужен curl внутри образа: make install-smoke-utils (или цель вызывает её самостоятельно не делает).
#
# Env:
#   SMOKE_REPOSITORY  — например library/busybox или jobber (без library/ для части приватных реестров)
#   SMOKE_REFERENCE   — тег (default: latest)
#   RUNTIME_ID        — default: podman
#   RIID_NAMESPACE    — default: riid-system
#   RIID_CONTAINER    — default: riid
set -euo pipefail

NS="${RIID_NAMESPACE:-riid-system}"
CONTAINER="${RIID_CONTAINER:-riid}"
REPO="${SMOKE_REPOSITORY:?SMOKE_REPOSITORY is required}"
REF="${SMOKE_REFERENCE:-latest}"
RUNTIME="${RUNTIME_ID:-podman}"

if [[ -n "${KUBECONFIG:-}" && ! -f "$KUBECONFIG" ]]; then
  echo "smoke-download-all-riid-pods: kubeconfig not found: $KUBECONFIG" >&2
  exit 1
fi

mapfile -t pods < <(kubectl -n "$NS" get pods -l 'app.kubernetes.io/name=riid' -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')

if ((${#pods[@]} == 0)); then
  echo "smoke-download-all-riid-pods: no pods with label app.kubernetes.io/name=riid in $NS" >&2
  exit 1
fi

failed=0
for pod in "${pods[@]}"; do
  [[ -z "$pod" ]] && continue
  phase=$(kubectl -n "$NS" get pod "$pod" -o jsonpath='{.status.phase}')
  if [[ "$phase" != Running ]]; then
    echo ">>> skip $pod (phase=$phase)" >&2
    continue
  fi
  echo ">>> $pod"
  if ! kubectl -n "$NS" exec -c "$CONTAINER" "$pod" -- \
    curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
    -H 'Content-Type: application/json' \
    -d "{\"repository\":\"${REPO}\",\"reference\":\"${REF}\",\"runtimeId\":\"${RUNTIME}\"}"; then
    echo "smoke-download-all-riid-pods: FAILED pod=$pod" >&2
    failed=1
  fi
done

exit "$failed"
