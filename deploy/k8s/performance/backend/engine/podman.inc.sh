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
#   1. The pull happens in the HOST netns, where the cluster resolver does not
#      exist. The reference therefore carries the registry's ClusterIP, not its
#      *.svc.cluster.local name — see riid_registry_node_host in common.inc.sh.
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

_podman_node_exec() {
  local riid_pod="$1" node node_pod
  shift
  node="$(kubectl -n "$NS" get pod "$riid_pod" -o jsonpath='{.spec.nodeName}')"
  node_pod="$(kubectl -n "$NS" get pods -l app.kubernetes.io/name=podman-node \
    --field-selector "spec.nodeName=$node,status.phase=Running" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  if [[ -z "$node_pod" ]]; then
    echo "no podman-node pod on node=$node for RIID pod=$riid_pod" >&2
    return 1
  fi
  kubectl -n "$NS" exec -c installer "$node_pod" -- chroot /host "$@"
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

engine_pull() {
  local pod="$1" ref="$2"
  _podman_node_exec "$pod" podman pull --tls-verify="$(_podman_tls_verify)" "$ref" >/dev/null
}

engine_pull_mirrored() {
  local pod="$1" ref="$2"
  _podman_node_exec "$pod" podman pull --tls-verify="$(_podman_tls_verify)" "$ref" >/dev/null
}

# Without this check the dfinit arm silently degrades into a plain pull and
# measures overhead instead of P2P — exactly how one run was already lost.
engine_mirror_check() {
  local pod="$1"
  if ! _podman_registries "$pod" | grep -qF "$RIID_DFINIT_PROXY_LOCATION"; then
    echo "dfinit mirror not found: the node daemon behind pod=$pod has no '$RIID_DFINIT_PROXY_LOCATION'" >&2
    echo "  dfinit writes /etc/containers/registries.conf on the NODE; check client.dfinit in values" >&2
    return 1
  fi
}

# The mirror image of the check above, for the baseline arm: the same node file
# serves both arms now, so a leftover dfinit mirror would turn this arm into a
# second dfinit run without anything looking wrong.
engine_no_mirror_check() {
  local pod="$1"
  if _podman_registries "$pod" | grep -qF "$RIID_DFINIT_PROXY_LOCATION"; then
    echo "baseline arm is contaminated: the node daemon behind pod=$pod still mirrors through" >&2
    echo "  '$RIID_DFINIT_PROXY_LOCATION'. Disable client.dfinit and let the node's" >&2
    echo "  /etc/containers/registries.conf go back to the pristine copy taken at install:" >&2
    echo "  /etc/containers/registries.conf.riid-baseline (src/engines/podman-node.yaml)" >&2
    return 1
  fi
}

# --volumes: a pull creates no anonymous volumes, so it does not affect the
# measurement, but it matches scenario/clear/clear-cache-all-riid-pods.sh —
# one meaning of "clean". Now wipes the node's store, which is the store both
# the baseline and the RIID import write into.
engine_clear_cache() {
  _podman_node_exec "$1" podman system prune -af --volumes >/dev/null
}
