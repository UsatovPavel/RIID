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

  # containerd's store is NOT covered by podman's prune, and a containerd arm
  # leaves ~20 GB behind. Two arms filled a 79 GB disk to 86% and kubelet then
  # evicted RIID mid-run, which surfaces as "pods not found" rather than as a
  # disk problem. Only the bench namespace is touched: k8s.io holds the
  # cluster's own images and removing those would break the node. The content
  # store is shared, so unreferenced content must be released too or the next
  # "cold" pull is warm.
  # Two namespaces, not one: the bench's own containerd backend pulls into
  # riid-bench, but RIID's ContainerdRuntimeAdapter uses containerd's default
  # namespace ("default"). Clearing only riid-bench left 18-19 images and ~40 GB
  # behind after every riid-containerd arm. k8s.io is the cluster's own images
  # and must never be touched.
  for ns in riid-bench default; do
    node_run "$alias" "printf '%s\n' '${STAND_PASSWORD}' | sudo -S ctr -n $ns images ls -q 2>/dev/null | while read -r img; do [ -n \"\$img\" ] && printf '%s\n' '${STAND_PASSWORD}' | sudo -S ctr -n $ns images rm --sync \"\$img\" >/dev/null 2>&1; done" >/dev/null 2>&1
    node_sudo "$alias" "ctr -n $ns content prune references >/dev/null 2>&1"
  done
  say "  containerd images left: riid-bench=$(node_run "$alias" "printf '%s\n' '${STAND_PASSWORD}' | sudo -S ctr -n riid-bench images ls -q 2>/dev/null | wc -l") default=$(node_run "$alias" "printf '%s\n' '${STAND_PASSWORD}' | sudo -S ctr -n default images ls -q 2>/dev/null | wc -l")"
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
# Dragonfly's own caches are part of the chain: /var/lib/dragonfly-run on each
# worker plus the seed-client PVCs under /opt/local-path-provisioner. Leaving
# them warm means a "cold" P2P arm measures a populated mesh - and they grow to
# ~12 GB per node, which is most of what fills the disk. Deleting files under a
# LIVE dfdaemon breaks it permanently ("No such file or directory (os error 2)"
# on every later fetch), so the data plane is drained first and only then wiped.
say "draining the Dragonfly data plane before clearing its caches"
kube -n dragonfly-system scale statefulset dragonfly-seed-client --replicas=0 >/dev/null 2>&1
# Verify the DaemonSet actually went to zero rather than assuming the patch
# landed: hiding its output once cost several runs, each silently skipping the
# cache wipe and quietly measuring a warm mesh.
for attempt in $(seq 1 12); do
  want=$(kube -n dragonfly-system get ds dragonfly-client \
    -o jsonpath='{.status.desiredNumberScheduled}' 2>/dev/null)
  [ "${want:-1}" = "0" ] && { say "  client DaemonSet drained to 0"; break; }
  kube -n dragonfly-system patch daemonset dragonfly-client --type strategic \
    -p '{"spec":{"template":{"spec":{"nodeSelector":{"riid.drain":"true"}}}}}' >/dev/null 2>&1
  sleep 10
done
[ "${want:-1}" = "0" ] || say "  WARNING: client DaemonSet still wants ${want:-?} pods"

for attempt in $(seq 1 60); do
  left=$(kube -n dragonfly-system get pods -l app=dragonfly -o name 2>/dev/null \
    | grep -cE 'seed-client|client-' || true)
  [ "${left:-0}" -eq 0 ] && break
  # Seed-client pods own PVCs and can sit in Terminating for a long time; past
  # the halfway mark, stop waiting politely and delete them outright, otherwise
  # the drain never finishes and the cache wipe is skipped every run.
  if [ "$attempt" -eq 30 ]; then
    say "  drain still has ${left} pod(s) after 5m - forcing deletion"
    kube -n dragonfly-system delete pods -l app=dragonfly --field-selector=status.phase!=Succeeded \
      --grace-period=30 --wait=false >/dev/null 2>&1
  fi
  sleep 10
done
say "  data plane drained (${left:-?} pods left)"

# Deleting these files under a live dfdaemon corrupts it permanently, and a
# corrupted mesh silently falls back to the registry - which looks like a valid
# arm. A warm cache only costs accuracy on this run; a corrupted one costs the
# whole stand, so an incomplete drain means skip, never "clear anyway".
if [ "${left:-1}" -ne 0 ]; then
  say "  WARNING: data plane did not fully drain - skipping the Dragonfly cache"
  say "  wipe. This arm will run against a WARM Dragonfly cache; treat its P2P"
  say "  numbers as unproven and re-run once the drain succeeds."
else
  i=0
  for alias in $STAND_SSH; do
    i=$((i+1)); [ "$i" = 1 ] && continue
    node_sudo "$alias" "rm -rf /var/lib/dragonfly-run/*"
    node_sudo "$alias" "sh -c 'rm -rf /opt/local-path-provisioner/*dragonfly-seed-client*/* 2>/dev/null'"
    say "  $alias dragonfly caches cleared"
  done
fi

say "bringing the Dragonfly data plane back"
kube -n dragonfly-system patch daemonset dragonfly-client --type json \
  -p '[{"op":"remove","path":"/spec/template/spec/nodeSelector/riid.drain"}]' >/dev/null 2>&1
kube -n dragonfly-system scale statefulset dragonfly-seed-client --replicas=3 >/dev/null 2>&1

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
