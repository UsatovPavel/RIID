#!/usr/bin/env bash
# podman driver. The engine is a daemon on the node (podman.socket, installed by
# src/engines/podman-node.yaml). The RIID image has no podman binary, so native
# benchmark commands execute the host client through the podman-node installer.
#
# PodmanRuntimeAdapter itself speaks Libpod HTTP directly over CONTAINER_HOST.
# Both paths operate on the same node image store.
#
# Two consequences of the engine living on the node, both of which used to be
# handled in the pod and are now checked instead:
#
#   1. The pull must happen in the HOST netns, and getting there takes nsenter:
#      chroot swaps the filesystem only, so `chroot /host podman pull` still runs
#      in the installer pod's Calico netns. The cluster resolver does not exist
#      there either, so the reference carries the registry's ClusterIP rather
#      than its *.svc.cluster.local name - see riid_registry_node_host.
#   2. dfinit edits the node's own /etc/containers/registries.conf, which is the
#      file the daemon reads. The baseline arm can no longer be pointed at a
#      private copy through CONTAINERS_REGISTRIES_CONF, because that variable
#      would be set on the client while the pull runs in the service. So the
#      baseline asserts the mirror is absent instead of arranging for it.
#
# Env:
#   PODMAN_TLS_VERIFY  force true|false; defaults to false for the local HTTP
#                        registry, true otherwise

_podman_tls_verify() {
  local v="${PODMAN_TLS_VERIFY:-${REGISTRY_TLS_VERIFY:-}}"
  if [[ -z "$v" ]]; then
    if riid_registry_is_plain_http; then v=false; else v=true; fi
  fi
  printf '%s\n' "$v"
}

# Cached per RIID pod in a local file: run-pull-scenario.sh starts the driver as a
# new process for every image, and resolving the podman-node pod each time put two
# API reads inside every measured pull - 4 calls against containerd's 2, and twice
# the exposure to a control-path stall.
_podman_node_pod_cache_file() {
  local dir="${RIID_PODMAN_NODE_CACHE_DIR:-${TMPDIR:-/tmp}/riid-podman-node-pods}"
  mkdir -p "$dir" 2>/dev/null || return 1
  printf '%s/%s.%s\n' "$dir" "$NS" "$1"
}

_podman_resolve_node_pod() {
  local riid_pod="$1" node node_pod attempt=1
  # riid_kubectl, not bare kubectl: it retries only failures to *reach* the API
  # (timeout/refused/no route/TLS), where the command never ran, so a retried pull
  # is still cold. A mid-stream break still fails. Nine rows of a dfinit-podman arm
  # died on API drops through a full-tunnel VPN on 2026-09-12 for want of this.
  node="$(riid_kubectl -n "$NS" get pod "$riid_pod" -o jsonpath='{.spec.nodeName}')" || return 1
  [[ -n "$node" ]] || { echo "RIID pod=$riid_pod has no node" >&2; return 1; }
  # Two pure reads, so an empty answer is retried as well: a slow API returned no
  # podman-node for a node whose pod was Running all along.
  while :; do
    node_pod="$(riid_kubectl -n "$NS" get pods -l app.kubernetes.io/name=podman-node \
      --field-selector "spec.nodeName=$node,status.phase=Running" \
      -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
    [[ -n "$node_pod" ]] && break
    if ((attempt >= ${RIID_NODE_POD_LOOKUP_RETRIES:-3})); then
      echo "no podman-node pod on node=$node for RIID pod=$riid_pod" >&2
      return 1
    fi
    attempt=$((attempt + 1))
    sleep 3
  done
  printf '%s\n' "$node_pod"
}

# nsenter into PID 1's netns (hostPID is on), not just chroot: dfinit points
# podman at the dfdaemon proxy on 127.0.0.1:4001, which lives in the HOST
# netns. From the pod's own netns that port is closed, podman reports
# "connection refused" and falls back to the registry without failing - which
# is how a whole dfinit-podman arm measured a plain pull on 2026-09-11 while
# `podman info` still listed the mirror, because reading config needs no socket.
_podman_exec_on() {
  local node_pod="$1"
  shift
  riid_kubectl -n "$NS" exec -c installer "$node_pod" -- nsenter -t 1 -n chroot /host "$@"
}

_podman_node_exec() {
  local riid_pod="$1" cache node_pod="" err rc=0
  shift
  cache="$(_podman_node_pod_cache_file "$riid_pod")" || cache=""
  [[ -n "$cache" ]] && node_pod="$(cat "$cache" 2>/dev/null || true)"
  if [[ -z "$node_pod" ]]; then
    node_pod="$(_podman_resolve_node_pod "$riid_pod")" || return 1
    [[ -z "$cache" ]] || printf '%s\n' "$node_pod" > "$cache" 2>/dev/null || true
  fi
  err="$(mktemp)"
  _podman_exec_on "$node_pod" "$@" 2>"$err" || rc=$?
  # A cached podman-node pod can be gone, restarted between images; the API then
  # refuses the exec before anything runs, so re-resolving keeps the retry cold.
  # Matched on kubectl's wording only: podman's own "404 page not found" means the
  # pull did run, and retrying that would measure it warm.
  if ((rc != 0)) && grep -qE '^Error from server \(NotFound\): pods "|unable to upgrade connection: (container not found|pod does not exist)' "$err"; then
    echo "podman-node pod $node_pod is gone, resolving again for RIID pod=$riid_pod" >&2
    [[ -z "$cache" ]] || rm -f "$cache"
    node_pod="$(_podman_resolve_node_pod "$riid_pod")" || { rm -f "$err"; return 1; }
    [[ -z "$cache" ]] || printf '%s\n' "$node_pod" > "$cache" 2>/dev/null || true
    rc=0
    _podman_exec_on "$node_pod" "$@" 2>"$err" || rc=$?
  fi
  cat "$err" >&2
  rm -f "$err"
  return "$rc"
}

# What the daemon resolved, not what some file says: this is the engine's own
# view of its registries, mirrors included (libpod/info.go:53 fills it from
# sysregistriesv2.GetRegistries).
_podman_registries() {
  _podman_node_exec "$1" podman info --format '{{json .Registries}}'
}

engine_preflight() {
  local pod="$1"
  riid_engine_exec "$pod" sh -ec '
    if command -v podman >/dev/null 2>&1; then
      echo "podman must not be installed in the RIID container" >&2
      exit 1
    fi
    if ! response="$(curl --fail --silent --show-error --unix-socket /run/podman/podman.sock http://d/_ping)"; then
      echo "Podman API is not reachable through /run/podman/podman.sock" >&2
      exit 1
    fi
    if [ "$response" != OK ]; then
      echo "unexpected Podman _ping response: $response" >&2
      exit 1
    fi
  '
  if ! _podman_node_exec "$pod" podman info >/dev/null; then
    echo "podman info failed on the node behind RIID pod=$pod" >&2
    _podman_node_exec "$pod" sh -c \
      'systemctl --no-pager --full status podman.socket podman.service; ss -lx | grep podman || true' >&2 || true
    return 1
  fi
}

# With no host the reference stays short: podman completes it through
# unqualified-search-registries, so this is a working case, not an error.
engine_ref() {
  local repo="$1" tag="$2" host
  host="$(riid_registry_node_host)" || return 1
  if [[ -n "$host" ]]; then
    printf '%s/%s:%s\n' "${host%/}" "$repo" "$tag"
  else
    printf '%s:%s\n' "$repo" "$tag"
  fi
}

# Driver hook for riid_pull_with_retry: a killed pull leaves partial blobs, and
# podman resumes them, so the retry has to start from no image at all.
engine_drop_image() {
  _podman_node_exec "$1" podman rmi -f "$2" >/dev/null 2>&1 || true
}

engine_pull() {
  local pod="$1" ref="$2"
  riid_pull_with_retry "$pod" "$ref" \
    _podman_node_exec "$pod" podman pull --tls-verify="$(_podman_tls_verify)" "$ref" >/dev/null
}

engine_pull_mirrored() {
  local pod="$1" ref="$2"
  riid_pull_with_retry "$pod" "$ref" \
    _podman_node_exec "$pod" podman pull --tls-verify="$(_podman_tls_verify)" "$ref" >/dev/null
}

# Whether the registry under test is mirrored - not whether the file mentions the
# proxy anywhere. scripts/values.yaml carries a hardcoded crio block for
# 10.96.5.146:5000 (an old stand's registry) and dfinit.enable is unconditional,
# so every stand gets a mirror for a registry it does not use. A substring check
# passes on that and the arm measures a plain pull, which is how every recorded
# dfinit-podman run ended up with baseline egress.
engine_mirror_location() {
  local pod="$1" host
  host="$(riid_registry_node_host)" || return 1
  [ -n "$host" ] || { echo "registry host is empty, set REGISTRY_PULL_HOST" >&2; return 1; }
  _podman_registries "$pod" | HOST="$host" python3 -c '
import json, os, sys
host = os.environ["HOST"]
try:
    regs = json.load(sys.stdin)
except Exception:
    sys.exit(2)
entry = regs.get(host)
if not isinstance(entry, dict):
    print("")
    sys.exit(0)
print(",".join(m.get("Location", "") for m in (entry.get("Mirrors") or [])))
'
}

# The pull runs on the node, so the proxy probe has to run there too.
engine_exec_where_pull_runs() { _podman_node_exec "$@"; }

# dfinit edits the node's own registries.conf, the same file the baseline reads,
# so the baseline has to assert the mirror is absent. See mirror-check.inc.sh.
engine_no_mirror_check() { riid_no_mirror_check "$@"; }

engine_clear_cache() {
  _podman_node_exec "$1" podman system prune -af --volumes >/dev/null
}
