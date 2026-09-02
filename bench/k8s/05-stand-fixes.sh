#!/usr/bin/env bash
# Stand-local fixes that the RIID manifests assume but a non-Porto, cloned
# VirtualBox stand does not provide. Idempotent.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

# 1. The RIID DaemonSet mounts /run/portod.socket with hostPath type: Socket,
# which kubelet asserts must exist - so RIID will not start on a node without
# Porto, even though the arm under test is podman or containerd. Bind an
# unconnected placeholder: nothing listens, so a real Porto call fails fast
# rather than appearing to work. /run is tmpfs, hence the boot-time unit.
placeholder="$(mktemp)"
cat > "$placeholder" <<'PY'
#!/usr/bin/env python3
"""Bind an unconnected AF_UNIX socket at /run/portod.socket (non-Porto stand)."""
import os
import socket

PATH = "/run/portod.socket"
if os.path.exists(PATH):
    os.unlink(PATH)
sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
sock.bind(PATH)
os.chmod(PATH, 0o666)
PY
unit="$(mktemp)"
cat > "$unit" <<'UNIT'
[Unit]
Description=Placeholder /run/portod.socket for the non-Porto RIID bench stand
Before=kubelet.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/sbin/riid-portod-placeholder

[Install]
WantedBy=multi-user.target
UNIT

i=0
for alias in $STAND_SSH; do
  i=$((i+1))
  [ "$i" = 1 ] && continue   # workers only; the control plane runs no RIID pod
  node_install_file "$alias" "$placeholder" /usr/local/sbin/riid-portod-placeholder 0755
  node_install_file "$alias" "$unit" /etc/systemd/system/riid-portod-placeholder.service 0644
  node_sudo "$alias" "systemctl daemon-reload"
  node_sudo "$alias" "systemctl enable riid-portod-placeholder >/dev/null 2>&1"
  node_sudo "$alias" "systemctl start riid-portod-placeholder"
  ok="$(node_run "$alias" 'python3 -c "import stat,os; print(stat.S_ISSOCK(os.stat(\"/run/portod.socket\").st_mode))"')"
  say "$alias: portod placeholder socket=${ok:-MISSING}"
done
rm -f "$placeholder" "$unit"

# 2. A minikube-era /etc/cni/net.d/1-k8s.conflist sorts before 10-calico.conflist
# ("1-" < "10-" lexically) and silently takes over pod networking while Calico
# still reports healthy: pods get IPs with no route and cross-node traffic dies.
for alias in $STAND_SSH; do
  if node_run "$alias" "test -f /etc/cni/net.d/1-k8s.conflist"; then
    say "$alias: removing stale minikube CNI config"
    node_sudo "$alias" "rm -f /etc/cni/net.d/1-k8s.conflist"
    node_sudo "$alias" "rm -rf /var/lib/cni/networks/bridge"
  fi
done
kube -n kube-system rollout restart daemonset/calico-node >/dev/null 2>&1
kube -n kube-system rollout status daemonset/calico-node --timeout=8m | tail -1
say "stand fixes applied"
