#!/usr/bin/env bash
# Give each cloned VM a unique identity and a static cluster address.
# Clones share hostname, machine-id and NAT IP; kubeadm needs all three unique.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

hosts_block="$(mktemp)"
i=0
for node in $STAND_NODES; do
  i=$((i+1))
  echo "$(stand_field "$STAND_IPS" "$i") ${node}" >> "$hosts_block"
done

i=0
for alias in $STAND_SSH; do
  i=$((i+1))
  node="$(stand_field "$STAND_NODES" "$i")"
  ip="$(stand_field "$STAND_IPS" "$i")"
  say "$alias -> ${node} (${ip})"

  node_sudo "$alias" "hostnamectl set-hostname ${node}"
  node_sudo "$alias" "rm -f /etc/machine-id"
  node_sudo "$alias" "systemd-machine-id-setup >/dev/null"

  netplan="$(mktemp)"
  cat > "$netplan" <<NETPLAN
network:
  version: 2
  ethernets:
    ${STAND_CLUSTER_NIC}:
      dhcp4: false
      addresses: [${ip}/24]
NETPLAN
  node_install_file "$alias" "$netplan" /etc/netplan/60-riid-hostonly.yaml 0600
  rm -f "$netplan"
  node_sudo "$alias" "netplan apply"

  # Every node must resolve every other node by name.
  node_sudo "$alias" "sed -i '/riid-n[0-9]/d' /etc/hosts"
  scp -q "$hosts_block" "$alias:/tmp/.stand-hosts"
  node_sudo "$alias" "sh -c 'cat /tmp/.stand-hosts >> /etc/hosts'"

  sleep 2
  say "  $(node_run "$alias" 'hostname; ip -4 -o addr show '"$STAND_CLUSTER_NIC"' | awk "{print \$4}"' | tr '\n' ' ')"
done
rm -f "$hosts_block"

say "verifying the mesh (this is what NAT-only silently fails)"
ok=1
for alias in $STAND_SSH; do
  for ip in $STAND_IPS; do
    node_run "$alias" "ping -c1 -W2 $ip >/dev/null 2>&1" || { say "  $alias cannot reach $ip"; ok=0; }
  done
done
[ "$ok" = 1 ] && say "full mesh OK" || die "mesh incomplete"
