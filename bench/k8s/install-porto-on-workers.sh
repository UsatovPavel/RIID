#!/usr/bin/env bash
# Installs Porto on the k8s workers so the riid-porto and bare-porto arms can
# run. dfinit has no Porto support, so those two are the whole Porto matrix.
#
# NOT SAFE TO RUN UNATTENDED. READ THIS FIRST.
#
# Porto 5.3.41 mounts its own cgroup v1 tree per controller and crash-loops on a
# pure-unified host ("Cannot mount cgroup: Device or resource busy", respawned by
# portod-master forever). The cgroup layout is fixed at kernel init, so the fix
# is a GRUB parameter plus a REBOOT of every worker - it cannot be done live.
# All three nodes are currently on pure cgroup2fs, so every worker needs it.
#
# That has a consequence for the measurements, not just for uptime: every arm
# measured after this runs on a differently-configured host than every arm
# measured before it. The gzip/zstd and prefix/non-prefix comparisons must
# therefore be COMPLETE before this script runs, or their two halves are not
# comparable to each other.
#
# The nodes are noble (24.04) and the only published deb is jammy (22.04). Its
# declared dependencies - libc6 >= 2.34, libncurses6, libtinfo6, logrotate - are
# all satisfied on noble, but the install is verified per node rather than
# assumed, and one node is done at a time so a failure costs one worker and not
# the cluster.
set -uo pipefail
STAND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
set -a; . "${STAND_DIR}/stand.env"; set +a
. "${STAND_DIR}/lib.sh"

PORTO_VERSION="${PORTO_VERSION:-5.3.41}"
DEB="${PORTO_DEB:-}"
WORKERS="${WORKERS:-aibox2 aibox3}"
# portod makes its registry requests from the host netns, so the address has to
# be one the node itself can reach, and it has no per-command --tls-verify=false:
# an HTTP registry must be named in docker_insecure_registry up front.
REGISTRY="${REGISTRY:-10.96.5.146:5000}"

say() { echo "[$(date +%H:%M:%S)] $*"; }

if [ -z "$DEB" ]; then
  DEB="/tmp/porto_jammy_${PORTO_VERSION}_amd64.deb"
  [ -f "$DEB" ] || curl -fsSL -m 600 -o "$DEB" \
    "https://github.com/ten-nancy/porto/releases/download/v${PORTO_VERSION}/porto_jammy_${PORTO_VERSION}_amd64.deb" \
    || { say "cannot fetch the porto deb"; exit 1; }
fi
say "using $DEB ($(du -h "$DEB" | cut -f1))"

conf=$(mktemp)
cat >"$conf" <<CONF
# docker_images_support is off by default, and without it portoctl docker-pull /
# docker-images / docker-rmi are unavailable entirely - the Porto arm in
# performance/backend/engine/porto.inc.sh is built on them.
container {
    docker_images_support: true
    docker_insecure_registry: "${REGISTRY}"
}
CONF

for w in $WORKERS; do
  say "=== $w ==="
  node_install_file "$w" "$DEB" /opt/riid-porto.deb 0644 || { say "$w: upload failed"; continue; }
  node_install_file "$w" "$conf" /tmp/10-riid-bench.conf 0644 || { say "$w: conf upload failed"; continue; }

  # Dry run first: a jammy package on noble is plausible, not certain, and a
  # broken dpkg state on a k8s worker is far more expensive than not trying.
  if ! node_sudo "$w" "apt-get install -y --simulate /opt/riid-porto.deb" | tail -3 | grep -q .; then
    say "$w: dependency simulation produced nothing - skipping"; continue
  fi
  say "$w: dependencies resolve"

  # The package's postinst starts portod, so the drop-in has to exist first.
  node_sudo "$w" "mkdir -p /etc/portod.conf.d"
  node_sudo "$w" "cp /tmp/10-riid-bench.conf /etc/portod.conf.d/10-riid-bench.conf"
  node_sudo "$w" "DEBIAN_FRONTEND=noninteractive apt-get install -y /opt/riid-porto.deb" >/dev/null

  if ! node_run "$w" "command -v portoctl" >/dev/null; then
    say "$w: portoctl absent after install - stopping, the stand is still intact"; exit 1
  fi
  say "$w: portoctl present"

  if node_run "$w" "grep -q systemd.unified_cgroup_hierarchy=0 /proc/cmdline"; then
    say "$w: already on legacy cgroups"
  else
    say "$w: switching to legacy cgroups and rebooting"
    node_sudo "$w" "sh -c 'grep -q unified_cgroup_hierarchy /etc/default/grub || sed -i \"s/^GRUB_CMDLINE_LINUX_DEFAULT=\\\"\\(.*\\)\\\"/GRUB_CMDLINE_LINUX_DEFAULT=\\\"\\1 systemd.unified_cgroup_hierarchy=0\\\"/\" /etc/default/grub'"
    node_sudo "$w" "update-grub" >/dev/null
    node_sudo "$w" "systemctl reboot" >/dev/null 2>&1
    sleep 20
    wait_ssh "$w" 60 || { say "$w: did not come back over ssh"; exit 1; }
    say "$w: back up, cgroup=$(node_run "$w" 'stat -fc %T /sys/fs/cgroup')"
  fi

  node_sudo "$w" "systemctl restart portod" >/dev/null 2>&1
  sleep 5
  if node_run "$w" "portoctl list" >/dev/null 2>&1; then
    say "$w: portod answering"
  else
    say "$w: portod NOT answering - $(node_run "$w" 'systemctl is-active portod')"
  fi
  wait_nodes_ready 40 || { say "$w: cluster did not return to Ready - stopping"; exit 1; }
  say "$w: cluster Ready"
done
rm -f "$conf"
say "done"
