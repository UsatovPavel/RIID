#!/usr/bin/env bash
# Registers this stand's kube-API address with the host's split-tunnel bypass
# set, so the bench control path stays on the direct route. Terraform hands out
# a new address per stand, and an unregistered one is routed into the tunnel,
# where a connect stall is charged to the measured pull. Idempotent.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KUBECONFIG_FILE="${1:-${KUBECONFIG:-$(cd "$HERE/.." && pwd)/serverConfig.yaml}}"
BYPASS_SET="${RIID_BYPASS_SET:-ru_bypass4}"
BYPASS_HOSTS="${RIID_BYPASS_HOSTS:-/etc/awg-ru-split.hosts}"
SYNC_CMD="${RIID_BYPASS_SYNC_CMD:-/usr/local/sbin/awg-ru-split-rules}"

# sudo -n, never an interactive prompt: this runs from `make stand`, which must
# not block overnight on a password. A missing privilege prints the command to
# run by hand instead.
as_root() {
  if [[ "${EUID}" -eq 0 ]]; then "$@"; else sudo -n "$@"; fi
}

if [[ ! -f "$KUBECONFIG_FILE" ]]; then
  echo "bypass: kubeconfig not found: $KUBECONFIG_FILE" >&2
  exit 2
fi

server="$(KUBECONFIG="$KUBECONFIG_FILE" kubectl config view --minify \
  -o jsonpath='{.clusters[0].cluster.server}' 2>/dev/null)"
host="$(printf '%s' "$server" \
  | sed -E 's#^[a-z0-9+.-]+://##; s#/.*$##; s#^\[([^]]*)\]:?[0-9]*$#\1#; s#:[0-9]+$##')"
if [[ -z "$host" ]]; then
  echo "bypass: no cluster server in $KUBECONFIG_FILE" >&2
  exit 2
fi

# Only IPv4: the bypass ipset is `family inet`, and a name that resolves to
# several addresses needs every one of them registered.
if [[ "$host" =~ ^[0-9]+(\.[0-9]+){3}$ ]]; then
  addrs=("$host")
else
  mapfile -t addrs < <(getent ahostsv4 "$host" 2>/dev/null | awk '{print $1}' | sort -u)
fi
if [[ "${#addrs[@]}" -eq 0 ]]; then
  echo "bypass: cannot resolve $host to an IPv4 address" >&2
  exit 2
fi

# The file first, and it is the durable half: the live set is destroyed when the
# split-tunnel unit stops, so an address that only ever reached the set is back
# in the tunnel after the next restart. The file is what that unit reads.
rc=0
added=0
for addr in "${addrs[@]}"; do
  grep -qxF "$addr" "$BYPASS_HOSTS" 2>/dev/null && continue
  # Plain append first: the file is meant to be owned by the user running the
  # stand, and sudo would fail it for nothing when it already is.
  if printf '%s\n' "$addr" >> "$BYPASS_HOSTS" 2>/dev/null \
     || printf '%s\n' "$addr" | as_root tee -a "$BYPASS_HOSTS" >/dev/null 2>&1; then
    echo "bypass: $addr added to $BYPASS_HOSTS"
    added=1
  else
    echo "bypass: cannot append to $BYPASS_HOSTS" >&2
    echo "  run: sudo sh -c 'echo $addr >> $BYPASS_HOSTS'" >&2
    rc=1
  fi
done

# One fixed privileged command rather than an `ipset add` per address: it is the
# only part that needs root, and a rule granting exactly this is far narrower
# than one granting `ipset add <set> *`.
if [[ "$added" -eq 1 ]]; then
  if as_root "$SYNC_CMD" sync 2>/dev/null; then
    echo "bypass: $BYPASS_SET reloaded from $BYPASS_HOSTS"
  else
    echo "bypass: cannot reload $BYPASS_SET, the addresses are queued in $BYPASS_HOSTS" >&2
    echo "  run: sudo $SYNC_CMD sync" >&2
    rc=1
  fi
fi

exit "$rc"
