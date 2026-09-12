#!/usr/bin/env bash
# Deletes the stand's PVCs while the cluster is still up, so Cinder CSI removes
# the volumes behind them itself - once the MKS cluster is gone nothing owns
# those volumes any more. Never touches kube-system: the CSI controller lives
# there and has to outlive every PVC it is asked to release.
set -euo pipefail

KUBECONFIG_FILE="${1:-${KUBECONFIG:-}}"
EXPECT_API="${2:-${EXPECT_API:-}}"
TIMEOUT="${PVC_DELETE_TIMEOUT:-300}"
PROTECTED_NS="kube-system"
SELF="delete-stand-pvcs"

if [[ -z "$KUBECONFIG_FILE" || ! -f "$KUBECONFIG_FILE" ]]; then
  echo "$SELF: kubeconfig not found ($KUBECONFIG_FILE) - leaving the volumes to purge-stand-volumes" >&2
  exit 0
fi
export KUBECONFIG="$KUBECONFIG_FILE"

# serverConfig.yaml outlives the stand it was written for, so the api address is
# checked before anything is deleted: a stale file would aim this at a cluster
# that is not being destroyed at all.
server="$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}' 2>/dev/null || true)"
if [[ -n "$EXPECT_API" && "$server" != *"$EXPECT_API"* ]]; then
  echo "$SELF: kubeconfig points at $server, not at $EXPECT_API - refusing to delete anything" >&2
  exit 0
fi

if ! kubectl version -o json >/dev/null 2>&1; then
  echo "$SELF: cluster unreachable - leaving the volumes to purge-stand-volumes" >&2
  exit 0
fi

mapfile -t namespaces < <(kubectl get pvc --all-namespaces \
  -o jsonpath='{range .items[*]}{.metadata.namespace}{"\n"}{end}' 2>/dev/null \
  | grep -vx "$PROTECTED_NS" | sort -u)

if [[ "${#namespaces[@]}" -eq 0 ]]; then
  echo "$SELF: no PVCs outside $PROTECTED_NS"
  exit 0
fi

echo "$SELF: namespaces with PVCs: ${namespaces[*]}"

# A PVC keeps its pvc-protection finalizer while a pod still mounts it, and a
# bare pod delete is undone by its controller - so the controllers go first.
for ns in "${namespaces[@]}"; do
  kubectl -n "$ns" delete statefulset,deployment,daemonset,replicaset,job \
    --all --ignore-not-found --timeout=60s >/dev/null 2>&1 || true
  kubectl -n "$ns" delete pod --all --ignore-not-found --grace-period=30 \
    --timeout=90s >/dev/null 2>&1 || true
done

for ns in "${namespaces[@]}"; do
  kubectl -n "$ns" delete pvc --all --ignore-not-found --wait=false >/dev/null 2>&1 || true
done

# The Cinder volume is gone only once its PV is, so the PV list is what to poll.
deadline=$(( $(date +%s) + TIMEOUT ))
while :; do
  left=0
  for ns in "${namespaces[@]}"; do
    left=$(( left + $(kubectl -n "$ns" get pvc --no-headers 2>/dev/null | wc -l) ))
  done
  pvs="$(kubectl get pv --no-headers 2>/dev/null | wc -l)"
  echo "$SELF: $left PVC(s), $pvs PV(s) left"
  [[ "$left" -eq 0 && "$pvs" -eq 0 ]] && break
  if [[ "$(date +%s)" -ge "$deadline" ]]; then
    echo "$SELF: timeout after ${TIMEOUT}s - purge-stand-volumes will pick up the rest" >&2
    break
  fi
  sleep 10
done

echo "$SELF: done"
