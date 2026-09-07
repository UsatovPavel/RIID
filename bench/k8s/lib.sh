#!/usr/bin/env bash
# Shared helpers for the local bench stand scripts.
set -uo pipefail

STAND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=stand.env
. "${STAND_DIR}/stand.env"

say() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '[%s] ERROR: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

# Index helpers: the four STAND_* lists are positional and must stay aligned.
stand_field() { echo "$1" | awk -v i="$2" '{print $i}'; }
stand_count() { echo "$STAND_VMS" | wc -w; }

# Run a command on a node as root. sudo has no TTY over `ssh host cmd` and no
# passwordless rule, so the password is piped in. NOTE: never combine this with
# a heredoc on the same invocation - the heredoc takes over stdin and sudo
# consumes it as the password, leaving the command with nothing to read.
node_sudo() {
  local host="$1"; shift
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" \
    "printf '%s\n' '${STAND_PASSWORD}' | sudo -S $*" 2>/dev/null
}

node_run() {
  local host="$1"; shift
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" "$@" 2>/dev/null
}

# Copy a locally-built file into place as root, avoiding the sudo/heredoc trap.
node_install_file() {
  local host="$1" src="$2" dst="$3" mode="${4:-0644}"
  scp -q "$src" "$host:/tmp/.stand-upload" || return 1
  node_sudo "$host" "cp /tmp/.stand-upload '$dst'"
  node_sudo "$host" "chmod $mode '$dst'"
}

wait_ssh() {
  local host="$1" tries="${2:-40}"
  for _ in $(seq 1 "$tries"); do
    ssh -o BatchMode=yes -o ConnectTimeout=6 "$host" true 2>/dev/null && return 0
    sleep 10
  done
  return 1
}

kube() { KUBECONFIG="$KUBECONFIG_OUT" kubectl "$@"; }

wait_nodes_ready() {
  local want="${1:-$(stand_count)}" tries="${2:-40}" n
  for _ in $(seq 1 "$tries"); do
    n=$(kube get nodes --no-headers 2>/dev/null | grep -c ' Ready') || n=0
    [ "$n" = "$want" ] && { say "nodes Ready: $n/$want"; return 0; }
    sleep 15
  done
  say "nodes Ready: ${n:-0}/$want (timed out)"
  return 1
}
