#!/usr/bin/env bash
# Host side: host-only network, per-VM SSH forwards, sizing, ssh config.
# Safe to re-run. VMs are powered off for the parts VirtualBox requires it.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

command -v VBoxManage >/dev/null || die "VBoxManage not found"

say "ensuring host-only network ${STAND_HOSTONLY_IF}"
if ! VBoxManage list hostonlyifs 2>/dev/null | grep -q "^Name: *${STAND_HOSTONLY_IF}$"; then
  VBoxManage hostonlyif create >/dev/null 2>&1 || die "could not create a host-only interface"
  say "created $(VBoxManage list hostonlyifs | awk '/^Name:/{print $2}' | tail -1)"
fi

say "powering off VMs so their hardware can be changed"
for vm in $STAND_VMS; do VBoxManage controlvm "$vm" acpipowerbutton >/dev/null 2>&1 || true; done
for _ in $(seq 1 30); do
  running=0
  for vm in $STAND_VMS; do VBoxManage list runningvms | grep -q "\"$vm\"" && running=1; done
  [ "$running" = 0 ] && break
  sleep 10
done

i=0
for vm in $STAND_VMS; do
  i=$((i+1))
  port="$(stand_field "$STAND_SSH_PORTS" "$i")"

  # Every clone inherits the same NAT forward, so only the first VM to boot can
  # bind it and the rest are unreachable. Give each its own host port.
  VBoxManage modifyvm "$vm" --natpf1 delete guestssh >/dev/null 2>&1 || true
  VBoxManage modifyvm "$vm" --natpf1 "guestssh,tcp,127.0.0.1,${port},,22" >/dev/null 2>&1

  # NAT alone cannot carry cluster traffic: NAT adapters are mutually isolated
  # and every clone gets the identical 10.0.2.15. Second adapter = cluster net.
  VBoxManage modifyvm "$vm" --nic2 hostonly --host-only-adapter2 "$STAND_HOSTONLY_IF" \
    --nictype2 virtio >/dev/null 2>&1
  VBoxManage modifyvm "$vm" --cpus "$VM_CPUS" --memory "$VM_MEMORY_MB" >/dev/null 2>&1

  vdi="$(VBoxManage showvminfo "$vm" --machinereadable | awk -F'"' '/^"SATA-0-0"=/{print $4}')"
  if [ -n "$vdi" ] && [ -f "$vdi" ]; then
    cur="$(VBoxManage showmediuminfo "$vdi" 2>/dev/null | awk '/^Capacity:/{print $2}')"
    if [ -n "$cur" ] && [ "$cur" -lt "$VM_DISK_MB" ]; then
      say "$vm: growing disk ${cur}MB -> ${VM_DISK_MB}MB"
      VBoxManage modifymedium disk "$vdi" --resize "$VM_DISK_MB" >/dev/null 2>&1 \
        || say "$vm: resize failed (snapshots present?), continuing"
    fi
  fi
  say "$vm: ssh 127.0.0.1:${port}, host-only nic2, ${VM_CPUS} cpu / ${VM_MEMORY_MB} MB"
done

say "writing ssh aliases"
i=0
for alias in $STAND_SSH; do
  i=$((i+1))
  port="$(stand_field "$STAND_SSH_PORTS" "$i")"
  if ! grep -q "^Host ${alias}\$" "$HOME/.ssh/config" 2>/dev/null; then
    cat >> "$HOME/.ssh/config" <<SSHCFG

Host ${alias}
    HostName 127.0.0.1
    Port ${port}
    User ${STAND_USER}
    StrictHostKeyChecking no
    UserKnownHostsFile /dev/null
SSHCFG
    say "added ssh alias ${alias} -> 127.0.0.1:${port}"
  fi
done

say "starting VMs"
for vm in $STAND_VMS; do VBoxManage startvm "$vm" --type headless >/dev/null 2>&1; done
for alias in $STAND_SSH; do
  wait_ssh "$alias" || die "$alias not reachable over ssh"
  say "$alias reachable"
done

# A bench arm that is cut in half by the laptop sleeping is a wasted arm.
if ! pgrep -f "systemd-inhibit.*RIID bench" >/dev/null 2>&1; then
  setsid systemd-inhibit --what=sleep:idle:handle-lid-switch --why="RIID bench" \
    sleep 86400 >/dev/null 2>&1 &
  say "sleep inhibited for 24h"
fi
say "host prepared"
