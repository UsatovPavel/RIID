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
CTR_KEEP_NAMESPACES="${CTR_KEEP_NAMESPACES:-k8s.io}"
CTR_ADDR="${CTR_ADDRESS:-}"
CLEAR_PODMAN="${CLEAR_PODMAN:-1}"
CLEAR_CONTAINERD="${CLEAR_CONTAINERD:-1}"
CLEAR_PORTO="${CLEAR_PORTO:-1}"

if [[ -n "${KUBECONFIG:-}" && ! -f "$KUBECONFIG" ]]; then
  echo "clear-cache-engines: kubeconfig not found: $KUBECONFIG" >&2
  exit 1
fi

# The workstation reaches the API over a VPN, and a dropped handshake here leaves
# a node holding the whole dataset while the arm reports a cold start - it happened
# on riid-bench-node-4ow4d at 20:38 on 2026-09-10. Retry only when the connection
# never opened; a real engine error still fails, which is the point of this script.
riid_kc() {
  local attempt=1 max="${CLEAR_CONNECT_RETRIES:-4}" err rc
  err="$(mktemp)"
  while :; do
    rc=0
    kubectl "$@" 2>"$err" || rc=$?
    cat "$err" >&2
    if ((rc != 0)) && ((attempt < max)) && grep -qE \
        'connect: connection (timed out|refused)|connect: no route to host|Unable to connect to the server|TLS handshake timeout|i/o timeout|error dialing backend' "$err"; then
      echo "clear-cache-engines: API unreachable, attempt $attempt/$max, retrying in 5s" >&2
      attempt=$((attempt + 1)); sleep 5; continue
    fi
    rm -f "$err"; return "$rc"
  done
}

mapfile -t node_pods < <(riid_kc -n "$NS" get pods -l "$PODMAN_NODE_LABEL" \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')

if ((${#node_pods[@]} == 0)); then
  echo "clear-cache-engines: no pods with label $PODMAN_NODE_LABEL in $NS" >&2
  exit 1
fi

failed=0

for pod in "${node_pods[@]}"; do
  [[ -z "$pod" ]] && continue
  phase=$(riid_kc -n "$NS" get pod "$pod" -o jsonpath='{.status.phase}')
  if [[ "$phase" != Running ]]; then
    echo ">>> skip $pod (phase=$phase)" >&2
    continue
  fi
  node="$(riid_kc -n "$NS" get pod "$pod" -o jsonpath='{.spec.nodeName}')"

  if [[ "$CLEAR_PODMAN" == 1 ]]; then
    echo ">>> podman prune: $node" >&2
    if ! riid_kc -n "$NS" exec -c installer "$pod" -- \
      chroot /host podman system prune -af --volumes >/dev/null; then
      echo "clear-cache-engines: FAILED podman prune node=$node" >&2
      failed=1
    fi
  fi

  # Every containerd namespace except the cluster's own, not just the one the
  # bench pulls into: `ctr images import` without -n lands in "default", which is
  # where RIID puts every containerd import, so clearing only riid-bench left
  # RIID's images in place and the next arm started warm.
  if [[ "$CLEAR_CONTAINERD" == 1 ]]; then
    echo ">>> containerd, all namespaces but [$CTR_KEEP_NAMESPACES]: $node" >&2
    if ! riid_kc -n "$NS" exec -c installer "$pod" -- chroot /host \
      env KEEP="$CTR_KEEP_NAMESPACES" CTR_ADDR="$CTR_ADDR" sh -ec '
        base="ctr"
        if [ -n "$CTR_ADDR" ]; then base="$base -a $CTR_ADDR"; fi
        left_total=0
        seen=0
        for ns in $($base namespaces ls -q 2>/dev/null); do
          skip=0
          IFS=,; for k in $KEEP; do [ "$ns" = "$k" ] && skip=1; done; unset IFS
          [ "$skip" = 1 ] && continue
          seen=$((seen + 1))
          $base -n "$ns" images ls -q 2>/dev/null | while read -r img; do
            [ -n "$img" ] && $base -n "$ns" images rm --sync "$img" >/dev/null 2>&1
          done
          $base -n "$ns" content prune references >/dev/null 2>&1 || true
          left=$($base -n "$ns" images ls -q 2>/dev/null | wc -l)
          echo "    images left in $ns: $left"
          left_total=$((left_total + left))
        done
        # Always one verdict per node, even when no namespace exists yet: on a
        # fresh stand the loop above prints nothing, and the gate cannot tell
        # "provably empty" from "the clear never ran".
        echo "    node containerd images left: $left_total (namespaces scanned: $seen)"
        [ "$left_total" -eq 0 ]
      '; then
      echo "clear-cache-engines: FAILED containerd cleanup node=$node" >&2
      failed=1
    fi
  fi

  # Porto keeps RIID's work as riid-layer-<digest> layers plus a marker layer per
  # image; podman prune and ctr never see them. Best effort: a stand without Porto
  # has only the stub socket and portoctl is absent.
  if [[ "$CLEAR_PORTO" == 1 ]]; then
    riid_kc -n "$NS" exec -c installer "$pod" -- chroot /host sh -ec '
      command -v portoctl >/dev/null 2>&1 || exit 0
      portoctl layer -L 2>/dev/null | awk "/^riid-/ {print \$1}" | while read -r l; do
        [ -n "$l" ] && portoctl layer -R "$l" >/dev/null 2>&1
      done
    ' >/dev/null 2>&1 || true
  fi
done

if ((failed != 0)); then
  echo "clear-cache-engines: finished with errors" >&2
  exit 1
fi
echo "clear-cache-engines: done on ${#node_pods[@]} node(s)"
