#!/usr/bin/env bash
# Put the stand into a valid cold-cache state for the next arm, and leave it
# verified. Everything here is a step an arm silently depends on; leaving any of
# it to per-run judgement is what keeps producing invalid arms.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

i=0
for alias in $STAND_SSH; do
  i=$((i+1)); [ "$i" = 1 ] && continue   # control plane runs no bench engine
  say "$alias: purging podman store and RIID work dir"
  node_sudo "$alias" "podman system prune -af --volumes >/dev/null 2>&1"
  node_sudo "$alias" "podman rmi -af >/dev/null 2>&1"
  node_sudo "$alias" "rm -rf /var/lib/riid/work/*"
  say "  $(node_run "$alias" 'df -h / | tail -1')"
done

# kubelet's DiskPressure condition has been observed stuck True while df shows
# tens of GB free. It keeps evicting RIID pods mid-arm, which shows up as a
# whole arm of fast non-zero exits rather than as an obvious infrastructure
# fault. Restarting kubelet forces it to re-evaluate; only do it when the disk
# really is fine, so a genuine full disk is still reported rather than masked.
say "checking for a stuck DiskPressure condition"
i=0
for alias in $STAND_SSH; do
  i=$((i+1))
  node="$(stand_field "$STAND_NODES" "$i")"
  cond="$(kube get node "$node" -o jsonpath='{.status.conditions[?(@.type=="DiskPressure")].status}' 2>/dev/null)"
  [ "$cond" = "True" ] || continue
  avail_gb="$(node_run "$alias" "df --output=avail -BG / | tail -1 | tr -dc '0-9'")"
  if [ "${avail_gb:-0}" -ge 20 ]; then
    say "  $node: DiskPressure=True with ${avail_gb}GB free - stuck, restarting kubelet"
    node_sudo "$alias" "systemctl restart kubelet"
    sleep 30
    say "  $node: now DiskPressure=$(kube get node "$node" -o jsonpath='{.status.conditions[?(@.type=="DiskPressure")].status}' 2>/dev/null)"
  else
    say "  $node: DiskPressure=True with only ${avail_gb}GB free - genuinely low, not masking it"
  fi
done

# podman's prune has repeatedly taken /etc/cni/net.d with it, which drops the
# workers to NotReady with "cni plugin not initialized" a minute or two later.
# Restarting calico-node restores the conflist; doing it unconditionally is far
# cheaper than discovering it halfway through the next arm.
say "restoring CNI (calico-node rollout)"
kube -n kube-system rollout restart daemonset/calico-node >/dev/null 2>&1
kube -n kube-system rollout status daemonset/calico-node --timeout=10m 2>&1 | tail -1
wait_nodes_ready "$(stand_count)" 40 || say "nodes still not all Ready"

# A RIID pod that survived the previous arm serves layers from its in-pod cache:
# one such pod turned a cold image from 557 s into 686 ms.
say "restarting RIID so no pod carries the previous arm's cache"
kube -n riid-system rollout restart daemonset/riid >/dev/null 2>&1
kube -n riid-system rollout status daemonset/riid --timeout=10m 2>&1 | tail -1

kube -n riid-system delete pods --field-selector=status.phase=Failed >/dev/null 2>&1
kube -n dragonfly-system delete pods --field-selector=status.phase=Failed >/dev/null 2>&1

say "waiting for the P2P data plane"
kube -n dragonfly-system rollout status daemonset/dragonfly-client --timeout=8m 2>&1 | tail -1
crash=$(kube -n dragonfly-system get pods -l app=dragonfly,component=client --no-headers 2>/dev/null | grep -c 'CrashLoopBackOff' || true)
if [ "${crash:-0}" -gt 0 ]; then
  say "dragonfly-client is crashlooping - check patch-dragonfly-client-hostip.sh idempotency"
fi
sleep 45   # let the mesh settle before an arm measures it

exec "${STAND_DIR}/06-verify.sh"
