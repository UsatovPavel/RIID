#!/usr/bin/env bash
# podman driver. The only engine that lives inside the bench pod: podman is
# installed into the RIID image (src/riid/Dockerfile.k8s) and needs no host socket.
#
# Env:
#   PODMAN_TLS_VERIFY               force true|false; defaults to false for the
#                                     local HTTP registry, true otherwise
#   PODMAN_BASELINE_REGISTRIES_CONF registries.conf without a mirror for the
#                                     baseline arm; empty = the file from the
#                                     image, which is the pre-dfinit behaviour

_podman_tls_verify() {
  local v="${PODMAN_TLS_VERIFY:-${REGISTRY_TLS_VERIFY:-}}"
  if [[ -z "$v" ]]; then
    if riid_registry_is_plain_http; then v=false; else v=true; fi
  fi
  printf '%s\n' "$v"
}

engine_preflight() {
  riid_engine_exec "$1" sh -ec 'command -v podman >/dev/null'
}

# With no host the reference stays short: podman completes it through
# unqualified-search-registries, so this is a working case, not an error.
engine_ref() {
  local repo="$1" tag="$2" host
  host="$(riid_registry_pull_host)"
  if [[ -n "$host" ]]; then
    printf '%s/%s:%s\n' "${host%/}" "$repo" "$tag"
  else
    printf '%s:%s\n' "$repo" "$tag"
  fi
}

engine_pull() {
  local pod="$1" ref="$2"
  if [[ -n "${PODMAN_BASELINE_REGISTRIES_CONF:-}" ]]; then
    # Baseline next to an enabled dfinit: the pod mounts hostPath
    # /etc/containers carrying the mirror, so the clean arm reads its own file.
    riid_engine_exec "$pod" env "CONTAINERS_REGISTRIES_CONF=${PODMAN_BASELINE_REGISTRIES_CONF}" \
      podman pull --tls-verify="$(_podman_tls_verify)" "$ref" >/dev/null
  else
    riid_engine_exec "$pod" podman pull --tls-verify="$(_podman_tls_verify)" "$ref" >/dev/null
  fi
}

# dfinit writes registries.conf on the host; the pod must mount it as a hostPath,
# otherwise podman takes the file from the image and the mirror never applies.
engine_pull_mirrored() {
  local pod="$1" ref="$2"
  riid_engine_exec "$pod" podman pull --tls-verify="$(_podman_tls_verify)" "$ref" >/dev/null
}

# Without this check the dfinit arm silently degrades into a plain pull and
# measures overhead instead of P2P — exactly how one run was already lost.
engine_mirror_check() {
  local pod="$1" conf="${PODMAN_REGISTRIES_CONF:-/etc/containers/registries.conf}"
  if ! riid_engine_exec "$pod" grep -qF "$RIID_DFINIT_PROXY_LOCATION" "$conf"; then
    echo "dfinit mirror not found: $conf in pod=$pod has no '$RIID_DFINIT_PROXY_LOCATION'" >&2
    echo "  check the /etc/containers hostPath on the bench pod and client.dfinit in values" >&2
    return 1
  fi
}

# --volumes: a pull creates no anonymous volumes, so it does not affect the
# measurement, but it matches scenario/clear/clear-cache-all-riid-pods.sh —
# one meaning of "clean".
engine_clear_cache() {
  riid_engine_exec "$1" podman system prune -af --volumes >/dev/null
}
