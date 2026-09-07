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
# ctr's --debug is a GLOBAL flag, so it must precede the subcommand. It logs every
# blob fetched - which layer, from where, how big - so an arm can be read per blob
# instead of per image. Output goes to stderr; CTR_DEBUG_LOG collects it per arm,
# and without that variable it just joins the arm's run.log.
CTR_DEBUG="${CONTAINERD_DEBUG:-0}"
CTR_DEBUG_LOG="${CONTAINERD_DEBUG_LOG:-}"

if ! [[ "$CTR_NAMESPACE" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  echo "CONTAINERD_NAMESPACE has unexpected characters: $CTR_NAMESPACE" >&2
  exit 2
fi

# ctr arguments that precede the subcommand, one per line (read with mapfile).
_ctr_base() {
  printf '%s\n' ctr
  if [[ "$CTR_DEBUG" == "1" ]]; then
    printf '%s\n' --debug
  fi
  if [[ -n "$CTR_ADDRESS" ]]; then
    printf '%s\n' -a "$CTR_ADDRESS"
  fi
  printf '%s\n' -n "$CTR_NAMESPACE"
}

# ctr writes per-blob progress to STDOUT - one line per manifest, config and layer,
# with the digest and state (waiting / already exists / downloading / done /
# extracted). The bench normally discards stdout, so debug mode keeps it; stderr is
# left alone so a real error still surfaces in the arm's run.log.
_ctr_run() {
  if [[ "$CTR_DEBUG" == "1" && -n "$CTR_DEBUG_LOG" ]]; then
    # Both pods pull the same image at once; sharing one file interleaves their
    # progress tables into nonsense, so give each pod its own.
    local out="${CTR_DEBUG_LOG%.log}-${pod}.log"
    printf '\n===== %s =====\n' "${IMAGE_REPOSITORY:-?}:${IMAGE_REFERENCE:-?} $(date -u +%H:%M:%S)" >> "$out"
    "$@" >> "$out"
  else
    "$@" > /dev/null
  fi
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
  host="$(riid_registry_node_host)" || return 1
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
  _ctr_run riid_engine_exec "$pod" "${base[@]}" images pull "${flags[@]}" "$ref"
}

# The mirror is switched on by an argument, not a file: without --hosts-dir the
# ctr client does not read certs.d at all, so the baseline and dfinit arms
# coexist on one node and the run order does not matter.
engine_pull_mirrored() {
  local pod="$1" ref="$2"
  local -a base flags
  mapfile -t base < <(_ctr_base)
  mapfile -t flags < <(_ctr_pull_flags)
  _ctr_run riid_engine_exec "$pod" "${base[@]}" images pull "${flags[@]}" --hosts-dir "$CTR_HOSTS_DIR" "$ref"
}

# containerd falls back to _default/hosts.toml when a registry has no entry of its
# own, and that catch-all carries no X-Dragonfly-Registry header: dfdaemon then
# takes the upstream from ?ns= and prefixes https:// against a plain-HTTP registry,
# so every task fails. A grep over the whole dir passes on exactly that state.
engine_mirror_check() {
  local pod="$1" host
  host="$(riid_registry_node_host)" || return 1
  if ! riid_engine_exec "$pod" env "DIR=$CTR_HOSTS_DIR" "HOST=$host" "LOC=$RIID_DFINIT_PROXY_LOCATION" sh -ec '
        d="$DIR/$HOST"
        [ -f "$d/hosts.toml" ] || d="$DIR/$(printf %s "$HOST" | sed "s/:\([0-9]*\)$/_\1_/")"
        [ -f "$d/hosts.toml" ] || { echo "no hosts.toml for $HOST under $DIR" >&2; exit 1; }
        grep -qF "$LOC" "$d/hosts.toml" || { echo "proxy $LOC missing in $d/hosts.toml" >&2; exit 1; }
        grep -qi "X-Dragonfly-Registry" "$d/hosts.toml" || { echo "no X-Dragonfly-Registry in $d/hosts.toml" >&2; exit 1; }
      '; then
    echo "dfinit mirror check failed for $host in pod=$pod" >&2
    echo "  only _default would be used, which has no header - re-run dfinit-enable ENGINE=containerd" >&2
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
