#!/usr/bin/env bash
# containerd driver. The engine lives on the node, not in the pod: the bench pod
# needs hostPath /run/containerd/containerd.sock and the ctr binary in its image
# — the same set the RIID => Containerd arm already requires
# (ContainerdRuntimeAdapter).
#
# By default `ctr images pull` goes through the transfer service, so the HTTP
# requests are made by the containerd daemon in the host netns. That is also
# where 127.0.0.1:4001, the dfdaemon proxy, is reachable. --hosts-dir and
# --plain-http work in that mode; --skip-verify/--tlscacert require --local.
#
# Env:
#   CONTAINERD_NAMESPACE   namespace for the bench (default riid-bench, not k8s.io)
#   CONTAINERD_ADDRESS     socket; empty = the ctr default
#   CONTAINERD_SNAPSHOTTER empty = the ctr default
#   CONTAINERD_HOSTS_DIR   where dfinit put certs.d (default /etc/containerd/certs.d)

CTR_NAMESPACE="${CONTAINERD_NAMESPACE:-riid-bench}"
CTR_ADDRESS="${CONTAINERD_ADDRESS:-}"
CTR_SNAPSHOTTER="${CONTAINERD_SNAPSHOTTER:-}"
CTR_HOSTS_DIR="${CONTAINERD_HOSTS_DIR:-/etc/containerd/certs.d}"

if ! [[ "$CTR_NAMESPACE" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  echo "CONTAINERD_NAMESPACE has unexpected characters: $CTR_NAMESPACE" >&2
  exit 2
fi

# ctr arguments that precede the subcommand, one per line (read with mapfile).
_ctr_base() {
  printf '%s\n' ctr
  if [[ -n "$CTR_ADDRESS" ]]; then
    printf '%s\n' -a "$CTR_ADDRESS"
  fi
  printf '%s\n' -n "$CTR_NAMESPACE"
}

# Flags of the pull itself, shared by the baseline and the mirror.
_ctr_pull_flags() {
  if riid_registry_is_plain_http; then
    printf '%s\n' --plain-http
  fi
  if [[ -n "$CTR_SNAPSHOTTER" ]]; then
    printf '%s\n' --snapshotter "$CTR_SNAPSHOTTER"
  fi
}

engine_preflight() {
  local pod="$1" addr="${CTR_ADDRESS:-/run/containerd/containerd.sock}"
  riid_engine_exec "$pod" env "CTR_SOCK=$addr" sh -ec '
    command -v ctr >/dev/null || { echo "ctr not found in pod" >&2; exit 1; }
    [ -S "$CTR_SOCK" ] || { echo "containerd socket not mounted: $CTR_SOCK" >&2; exit 1; }
  '
}

# ctr needs a fully qualified reference: it has no unqualified-search-registries
# like podman, so it will not complete a short repo:tag.
engine_ref() {
  local repo="$1" tag="$2" host
  host="$(riid_registry_pull_host)"
  if [[ -z "$host" ]]; then
    echo "containerd: registry host is empty, set REGISTRY_PULL_HOST (ctr needs a fully qualified ref)" >&2
    return 2
  fi
  printf '%s/%s:%s\n' "${host%/}" "$repo" "$tag"
}

engine_pull() {
  local pod="$1" ref="$2"
  local -a base flags
  mapfile -t base < <(_ctr_base)
  mapfile -t flags < <(_ctr_pull_flags)
  riid_engine_exec "$pod" "${base[@]}" images pull "${flags[@]}" "$ref" >/dev/null
}

# The mirror is switched on by an argument, not a file: without --hosts-dir the
# ctr client does not read certs.d at all, so the baseline and dfinit arms
# coexist on one node and the run order does not matter.
engine_pull_mirrored() {
  local pod="$1" ref="$2"
  local -a base flags
  mapfile -t base < <(_ctr_base)
  mapfile -t flags < <(_ctr_pull_flags)
  riid_engine_exec "$pod" "${base[@]}" images pull "${flags[@]}" --hosts-dir "$CTR_HOSTS_DIR" "$ref" >/dev/null
}

engine_mirror_check() {
  local pod="$1"
  if ! riid_engine_exec "$pod" env "DIR=$CTR_HOSTS_DIR" "LOC=$RIID_DFINIT_PROXY_LOCATION" sh -ec '
        [ -d "$DIR" ] || { echo "hosts dir missing: $DIR" >&2; exit 1; }
        grep -rqF "$LOC" "$DIR"
      '; then
    echo "dfinit mirror not found: $CTR_HOSTS_DIR in pod=$pod has no '$RIID_DFINIT_PROXY_LOCATION'" >&2
    echo "  check the /etc/containerd hostPath on the bench pod and client.dfinit in values" >&2
    return 1
  fi
}

# The content store is shared across namespaces, so removing the images is not
# enough — unreferenced content has to be released too, otherwise the next
# "cold" pull is warm.
engine_clear_cache() {
  local pod="$1"
  riid_engine_exec "$pod" env "CTR_NS=$CTR_NAMESPACE" "CTR_ADDR=$CTR_ADDRESS" sh -ec '
    set -- ctr
    if [ -n "$CTR_ADDR" ]; then set -- "$@" -a "$CTR_ADDR"; fi
    set -- "$@" -n "$CTR_NS"
    "$@" images ls -q | while read -r img; do
      [ -n "$img" ] || continue
      "$@" images rm --sync "$img" >/dev/null
    done
    "$@" content prune references >/dev/null
  '
}
