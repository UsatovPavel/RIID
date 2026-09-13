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
#   CLEAR_PORTO         — 1/0, default 1; a node without portod reports "absent"
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

. "$(dirname "${BASH_SOURCE[0]}")/kubectl-retry.inc.sh"

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

  # The Porto stand runs no podman (podman-node puts a stub socket there), and a
  # failing prune would refuse every arm on it. Absent is said, not assumed.
  if [[ "$CLEAR_PODMAN" == 1 ]]; then
    echo ">>> podman prune: $node" >&2
    if ! riid_kc -n "$NS" exec -c installer "$pod" -- chroot /host sh -c 'command -v podman' >/dev/null 2>&1; then
      echo "    podman absent on $node, prune skipped"
    elif ! riid_kc -n "$NS" exec -c installer "$pod" -- \
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

  # Porto holds two stores: docker-pull images (bare-porto) and riid-layer-* layers
  # plus markers (riid-porto). One verdict per node, as for containerd, so the gate
  # can prove a cold start; a stand without portod (the MKS stub socket) says absent.
  if [[ "$CLEAR_PORTO" == 1 ]]; then
    echo ">>> porto images and riid layers: $node" >&2
    if ! riid_kc -n "$NS" exec -c installer "$pod" -- chroot /host sh -ec '
      if [ ! -x /usr/sbin/portod ] || ! timeout 10 portoctl list >/dev/null 2>&1; then
        echo "    node porto: absent"
        exit 0
      fi
      imgs=$(portoctl docker-images)
      printf "%s\n" "$imgs" | awk "NR > 1 && NF >= 2 { print \$2 }" | while read -r img; do
        [ -n "$img" ] && portoctl docker-rmi "$img" >/dev/null 2>&1
      done
      portoctl layer -L | awk "/^riid-/ { print \$1 }" | while read -r l; do
        [ -n "$l" ] && portoctl layer -R "$l" >/dev/null 2>&1
      done
      left_imgs=$(portoctl docker-images | awk "NR > 1 && NF >= 2" | wc -l)
      left_layers=$(portoctl layer -L | grep -c "^riid-" || true)
      echo "    node porto images left: $left_imgs layers left: $left_layers"
      [ "$left_imgs" -eq 0 ] && [ "$left_layers" -eq 0 ]
    '; then
      echo "clear-cache-engines: FAILED porto cleanup node=$node" >&2
      failed=1
    fi
  fi

  # bare.sh caches its preflight verdict in the RIID pod /tmp, a hostPath; left
  # behind, it skips the check on the next arm even if the engine broke since.
  riid_kc -n "$NS" exec -c installer "$pod" -- chroot /host \
    sh -c 'rm -f /var/lib/riid/tmp/.riid-bare-preflight-ok-*' >/dev/null 2>&1 || true
done

if ((failed != 0)); then
  echo "clear-cache-engines: finished with errors" >&2
  exit 1
fi
echo "clear-cache-engines: done on ${#node_pods[@]} node(s)"
