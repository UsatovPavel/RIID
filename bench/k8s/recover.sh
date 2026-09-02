#!/usr/bin/env bash
# The stand's most common failure is not a bug: the laptop rebooted and the VMs
# are simply off. Bring them back and re-assert the boot-fragile pieces.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

for vm in $STAND_VMS; do
  if VBoxManage list runningvms | grep -q "\"$vm\""; then
    say "$vm already running"
  else
    say "starting $vm"
    VBoxManage startvm "$vm" --type headless >/dev/null 2>&1
  fi
done
for alias in $STAND_SSH; do wait_ssh "$alias" || die "$alias not reachable"; done

if ! pgrep -f "systemd-inhibit.*RIID bench" >/dev/null 2>&1; then
  setsid systemd-inhibit --what=sleep:idle:handle-lid-switch --why="RIID bench" \
    sleep 86400 >/dev/null 2>&1 &
  say "sleep inhibited"
fi

wait_nodes_ready "$(stand_count)" || say "nodes not all Ready yet - see 06-verify.sh"
exec "${STAND_DIR}/06-verify.sh"
