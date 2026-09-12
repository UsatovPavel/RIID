#!/usr/bin/env bash
# Repairs a stand that an engine install left in a state no arm can measure.
# Called from bootstrap/Makefile; both actions are idempotent and safe by hand:
#   debug.sh restore-registries-conf   undo the dfinit mirror on every worker
#   debug.sh repair-cni                put calico's conflist back after podman
set -uo pipefail

usage() {
  echo "usage: debug.sh restore-registries-conf|repair-cni" >&2
  exit 2
}

require_kubeconfig() {
  [ -n "${KUBECONFIG:-}" ] || { echo "debug.sh: KUBECONFIG is not set" >&2; exit 1; }
  [ -f "$KUBECONFIG" ] || { echo "kubeconfig not found: $KUBECONFIG" >&2; exit 1; }
  local ctx
  ctx="$(kubectl config current-context)" || exit 1
  kubectl config use-context "$ctx" >/dev/null
}

# dfinit edits the node's /etc/containers/registries.conf and turning dfinit off
# does not undo it, so a baseline arm would keep pulling through the mirror and
# quietly become a second dfinit run. podman-node.yaml keeps a pristine copy
# taken before dfinit ever touched the file; this puts it back on every worker.
restore_registries_conf() {
  local fail=0 done_n=0 p
  for p in $(kubectl -n riid-system get pods -l app.kubernetes.io/name=podman-node \
      -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'); do
    [ -n "$p" ] || continue
    if kubectl -n riid-system exec -c installer "$p" -- chroot /host sh -ec '
         [ -f /etc/containers/registries.conf.riid-baseline ] || { echo "no pristine copy"; exit 1; }
         cp /etc/containers/registries.conf.riid-baseline /etc/containers/registries.conf'; then
      done_n=$((done_n + 1))
    else
      echo "restore-registries-conf: FAILED on $p" >&2
      fail=1
    fi
  done
  echo "restore-registries-conf: restored on $done_n node(s)"
  [ "$fail" = 0 ] || {
    echo "restore-registries-conf: a node kept the mirror - the baseline arm would measure dfinit" >&2
    exit 1
  }
}

# Installing podman drops containernetworking-plugins into /etc/cni/net.d, which
# belongs to the cluster CNI - nodes have gone NotReady with "cni plugin not
# initialized" minutes after the package landed. Rolling calico-node puts its
# conflist back. Cheap, idempotent, and the alternative is noticing by hand.
repair_cni() {
  local notready
  echo ">>> repair-cni: rolling calico-node after the engine install"
  kubectl -n kube-system rollout restart daemonset/calico-node >/dev/null
  kubectl -n kube-system rollout status daemonset/calico-node --timeout=10m
  notready=$(kubectl get nodes --no-headers | awk '$2 != "Ready"' | wc -l)
  [ "$notready" -eq 0 ] || {
    echo "repair-cni: $notready node(s) still not Ready:" >&2
    kubectl get nodes --no-headers | awk '$2 != "Ready"' >&2
    exit 1
  }
  echo "repair-cni: every node Ready"
}

case "${1:-}" in
  restore-registries-conf) require_kubeconfig; restore_registries_conf ;;
  repair-cni)              require_kubeconfig; repair_cni ;;
  *)                       usage ;;
esac
