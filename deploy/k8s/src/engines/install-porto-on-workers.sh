#!/usr/bin/env bash
# Installs Porto on the k8s workers so the riid-porto and bare-porto arms can
# run; dfinit has no Porto support, so those two are the whole Porto matrix.
# Stand agnostic: workers are ssh targets in PORTO_WORKERS and readiness comes
# from KUBECONFIG, so the local stand and a Selectel one use this one file.

# NOT SAFE TO RUN UNATTENDED. Porto 5.3.x crash-loops on a pure-unified host
# ("Cannot mount cgroup: Device or resource busy"); the cgroup layout is fixed at
# kernel init, so the fix is a GRUB parameter plus a REBOOT of every worker.
# It cannot be done live, and every node here needs it.

# It also changes what later arms measure: every arm after this runs on a
# differently-configured host than every arm before. Finish the gzip/zstd and
# prefix/non-prefix comparisons BEFORE running this, or their halves are not
# comparable to each other. Env: PORTO_WORKERS, PORTO_SSH_PASSWORD, PORTO_REGISTRY.
set -uo pipefail

PORTO_VERSION="${PORTO_VERSION:-5.3.58}"
PORTO_RELEASE_REPO="${PORTO_RELEASE_REPO:-UsatovPavel/porto}"
DEB="${PORTO_DEB:-}"
WORKERS="${PORTO_WORKERS:-}"
SSH_PASSWORD="${PORTO_SSH_PASSWORD:-}"
# portod makes its registry requests from the host netns, so the address has to be
# one the node itself can reach, and it has no per-command --tls-verify=false: an
# HTTP registry must be named in docker_insecure_registry up front.
REGISTRY="${PORTO_REGISTRY:-}"

say() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '[%s] ERROR: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

[ -n "$WORKERS" ]      || die "set PORTO_WORKERS to the ssh targets of the worker nodes"
[ -n "$SSH_PASSWORD" ] || die "set PORTO_SSH_PASSWORD (sudo -S has no TTY over 'ssh host cmd')"
[ -n "$REGISTRY" ]     || die "set PORTO_REGISTRY to an address the node itself can reach"

# sudo has no TTY over `ssh host cmd` and no passwordless rule, so the password is
# piped in. Never combine with a heredoc: it takes over stdin and sudo eats it.
node_sudo() {
  local host="$1"; shift
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" \
    "printf '%s\n' '${SSH_PASSWORD}' | sudo -S $*" 2>/dev/null
}

node_run() {
  local host="$1"; shift
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" "$@" 2>/dev/null
}

node_install_file() {
  local host="$1" src="$2" dst="$3" mode="${4:-0644}"
  scp -q "$src" "$host:/tmp/.porto-upload" || return 1
  node_sudo "$host" "cp /tmp/.porto-upload '$dst'"
  node_sudo "$host" "chmod $mode '$dst'"
}

wait_ssh() {
  local host="$1" tries="${2:-60}"
  for _ in $(seq 1 "$tries"); do
    ssh -o BatchMode=yes -o ConnectTimeout=6 "$host" true 2>/dev/null && return 0
    sleep 10
  done
  return 1
}

# A reboot takes the node out of the cluster; the next worker is not touched until
# every node is back, so a failure costs one worker and not the stand.
wait_nodes_ready() {
  local tries="${1:-40}" total ready
  command -v kubectl >/dev/null 2>&1 || { say "kubectl absent, skipping the cluster check"; return 0; }
  for _ in $(seq 1 "$tries"); do
    total=$(kubectl get nodes --no-headers 2>/dev/null | wc -l)
    ready=$(kubectl get nodes --no-headers 2>/dev/null | grep -c ' Ready')
    if [ "$total" -gt 0 ] && [ "$total" = "$ready" ]; then
      say "nodes Ready: $ready/$total"
      return 0
    fi
    sleep 15
  done
  say "nodes Ready: ${ready:-0}/${total:-0} (timed out)"
  return 1
}

if [ -z "$DEB" ]; then
  DEB="/tmp/porto_jammy_${PORTO_VERSION}_amd64.deb"
  # The nodes are noble (24.04) and the only published deb is jammy (22.04). Its
  # dependencies are satisfied on noble, but each install is verified per node.
  [ -f "$DEB" ] || curl -fsSL -m 600 -o "$DEB" \
    "https://github.com/${PORTO_RELEASE_REPO}/releases/download/v${PORTO_VERSION}/porto_jammy_${PORTO_VERSION}_amd64.deb" \
    || die "cannot fetch the porto deb"
fi
say "using $DEB ($(du -h "$DEB" | cut -f1))"

conf=$(mktemp)
trap 'rm -f "$conf"' EXIT
cat >"$conf" <<CONF
# docker_images_support is off by default, and without it portoctl docker-pull /
# docker-images / docker-rmi are unavailable entirely - the Porto arm in
# performance/backend/engine/porto.inc.sh is built on them.
daemon {
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
say "done"
