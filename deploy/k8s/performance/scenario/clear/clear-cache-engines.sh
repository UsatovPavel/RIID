#!/usr/bin/env bash
# Engine stores only — for arms that never touch Dragonfly or RIID (bare-*).
# Two engines share one node: podman's own store and containerd's riid-bench
# namespace. Both are reached through the podman-node DaemonSet's chroot /host,
# which is the only pod on a worker that can see the host filesystem.
#
# Deliberately does NOT restart the Dragonfly control plane, wipe dfdaemon stores
# or roll the RIID DaemonSet: a baseline arm has no reason to pay for that, and
# clear-cache-df-riid.sh already does it for the arms that do.
#
# Env:
#   RIID_NAMESPACE      — default: riid-system
#   PODMAN_NODE_LABEL   — default: app.kubernetes.io/name=podman-node
#   CTR_NAMESPACE       — default: riid-bench (containerd namespace the bench pulls into)
#   CTR_ADDRESS         — default: empty, i.e. containerd's own default socket
#   CLEAR_PODMAN        — 1/0, default 1
#   CLEAR_CONTAINERD    — 1/0, default 1
set -euo pipefail

NS="${RIID_NAMESPACE:-riid-system}"
PODMAN_NODE_LABEL="${PODMAN_NODE_LABEL:-app.kubernetes.io/name=podman-node}"
CTR_NS="${CTR_NAMESPACE:-riid-bench}"
CTR_ADDR="${CTR_ADDRESS:-}"
CLEAR_PODMAN="${CLEAR_PODMAN:-1}"
CLEAR_CONTAINERD="${CLEAR_CONTAINERD:-1}"

if [[ -n "${KUBECONFIG:-}" && ! -f "$KUBECONFIG" ]]; then
  echo "clear-cache-engines: kubeconfig not found: $KUBECONFIG" >&2
  exit 1
fi

mapfile -t node_pods < <(kubectl -n "$NS" get pods -l "$PODMAN_NODE_LABEL" \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')

if ((${#node_pods[@]} == 0)); then
  echo "clear-cache-engines: no pods with label $PODMAN_NODE_LABEL in $NS" >&2
  exit 1
fi

failed=0

for pod in "${node_pods[@]}"; do
  [[ -z "$pod" ]] && continue
  phase=$(kubectl -n "$NS" get pod "$pod" -o jsonpath='{.status.phase}')
  if [[ "$phase" != Running ]]; then
    echo ">>> skip $pod (phase=$phase)" >&2
    continue
  fi
  node="$(kubectl -n "$NS" get pod "$pod" -o jsonpath='{.spec.nodeName}')"

  if [[ "$CLEAR_PODMAN" == 1 ]]; then
    echo ">>> podman prune: $node" >&2
    if ! kubectl -n "$NS" exec -c installer "$pod" -- \
      chroot /host podman system prune -af --volumes >/dev/null; then
      echo "clear-cache-engines: FAILED podman prune node=$node" >&2
      failed=1
    fi
  fi

  # containerd keeps images and their blobs separately: dropping the images alone
  # leaves the content store populated and the next "cold" pull resolves locally.
  if [[ "$CLEAR_CONTAINERD" == 1 ]]; then
    echo ">>> containerd namespace $CTR_NS: $node" >&2
    if ! kubectl -n "$NS" exec -c installer "$pod" -- chroot /host \
      env CTR_NS="$CTR_NS" CTR_ADDR="$CTR_ADDR" sh -ec '
        set -- ctr
        if [ -n "$CTR_ADDR" ]; then set -- "$@" -a "$CTR_ADDR"; fi
        set -- "$@" -n "$CTR_NS"
        "$@" images ls -q 2>/dev/null | while read -r img; do
          [ -n "$img" ] && "$@" images rm --sync "$img" >/dev/null 2>&1
        done
        "$@" content prune references >/dev/null 2>&1 || true
        left=$("$@" images ls -q 2>/dev/null | wc -l)
        echo "    images left in $CTR_NS: $left"
        [ "$left" -eq 0 ]
      '; then
      echo "clear-cache-engines: FAILED containerd cleanup node=$node" >&2
      failed=1
    fi
  fi
done

if ((failed != 0)); then
  echo "clear-cache-engines: finished with errors" >&2
  exit 1
fi
echo "clear-cache-engines: done on ${#node_pods[@]} node(s)"
