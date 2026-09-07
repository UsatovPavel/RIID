#!/usr/bin/env bash
# Assert the stand is actually usable. Every check here corresponds to a
# failure that once looked healthy from the outside.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
fail=0
chk() { if [ "$1" = 0 ]; then say "  OK   $2"; else say "  FAIL $2"; fail=$((fail+1)); fi; }

say "cluster"
n=$(kube get nodes --no-headers 2>/dev/null | grep -c ' Ready'); chk $([ "$n" = "$(stand_count)" ] && echo 0 || echo 1) "nodes Ready ($n/$(stand_count))"

# A node whose InternalIP is the NAT address means --node-ip never took effect.
bad=$(kube get nodes -o jsonpath='{range .items[*]}{.status.addresses[?(@.type=="InternalIP")].address}{"\n"}{end}' 2>/dev/null | grep -c '^10\.0\.2\.' || true)
chk $([ "${bad:-0}" = 0 ] && echo 0 || echo 1) "node InternalIPs are on the cluster network"

dp=$(kube get nodes -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="DiskPressure")].status}{"\n"}{end}' 2>/dev/null | grep -c True || true)
chk $([ "${dp:-0}" = 0 ] && echo 0 || echo 1) "no node under DiskPressure"

say "cni"
for alias in $STAND_SSH; do
  node_run "$alias" "test -f /etc/cni/net.d/10-calico.conflist" && r=0 || r=1
  chk $r "$alias has calico conflist"
  node_run "$alias" "test -f /etc/cni/net.d/1-k8s.conflist" && r=1 || r=0
  chk $r "$alias free of the stale minikube conflist"
done

say "engine sockets on workers"
i=0
for alias in $STAND_SSH; do
  i=$((i+1)); [ "$i" = 1 ] && continue
  node_run "$alias" 'python3 -c "import stat,os,sys; sys.exit(0 if stat.S_ISSOCK(os.stat(\"/run/portod.socket\").st_mode) else 1)"' && r=0 || r=1
  chk $r "$alias /run/portod.socket is a socket (RIID DaemonSet asserts it)"
done

say "P2P socket reachable from RIID"
i=0
for alias in $STAND_SSH; do
  i=$((i+1)); [ "$i" = 1 ] && continue
  # Read the path from the live DaemonSet rather than hardcoding it: the socket
  # dir was moved off tmpfs (/run is 873 MB of RAM, far too small for an 11 GiB
  # dataset) and a hardcoded check would then pass or fail for the wrong reason.
  SOCK_DIR="$(kube -n dragonfly-system get ds dragonfly-client \
    -o jsonpath='{.spec.template.spec.volumes[?(@.name=="socket-dir")].hostPath.path}' 2>/dev/null)"
  SOCK_DIR="${SOCK_DIR:-/var/run/dragonfly}"
  node_run "$alias" "test -S ${SOCK_DIR}/dfdaemon.sock" && r=0 || r=1
  chk $r "$alias has ${SOCK_DIR}/dfdaemon.sock (RIID falls back to the registry without it, silently)"
done

say "disk headroom (dataset needs ~20 GB unpacked, kubelet reserves ~6.3 GB)"
i=0
for alias in $STAND_SSH; do
  i=$((i+1)); [ "$i" = 1 ] && continue
  avail=$(node_run "$alias" "df --output=avail -BG / | tail -1 | tr -dc '0-9'")
  chk $([ "${avail:-0}" -ge 27 ] && echo 0 || echo 1) "$alias has ${avail:-?} GB free (want >= 27)"
done

say "$( [ "$fail" = 0 ] && echo 'stand verified' || echo "$fail check(s) FAILED" )"
exit "$fail"
