#!/usr/bin/env bash
# Porto driver. The engine lives on the node, like containerd: the bench pod
# needs hostPath /run/portod.socket and the portoctl binary in its image — the
# same set the RIID => Porto arm already requires (PortoRuntimeAdapter).
#
# portod itself talks to the registry through `portoctl docker-pull`, which puts
# two requirements on the stand that podman and containerd do not have:
#
#   1. /etc/portod.conf must carry `container { docker_images_support: true }` —
#      without it docker-pull/docker-images/docker-rmi are disabled entirely.
#   2. An HTTP registry is listed there too, in `docker_insecure_registry`: Porto
#      has no command-level flag like podman's --tls-verify=false or ctr's
#      --plain-http.
#
# And separately about the host name: the HTTP requests are made by portod in the
# host netns, where there is no cluster resolver. A service name such as
# *.svc.cluster.local does not resolve from there, so REGISTRY_PULL_HOST for this
# arm has to point at an address visible from the node (the registry NodePort or
# an external registry).
#
# Env:
#   PORTO_PLACE    -P <place>; empty = the default /place
#   PORTO_PLATFORM -T amd64|aarch64; empty = the portod default
#   PORTO_SOCKET   portod socket used by preflight (default /run/portod.socket)
#   DOCKER_TOKEN   registry token passed to docker-pull, as Porto documents

PORTO_SOCKET="${PORTO_SOCKET:-/run/portod.socket}"
PORTO_PLACE="${PORTO_PLACE:-}"
PORTO_PLATFORM="${PORTO_PLATFORM:-}"

# portoctl arguments preceding the subcommand and the shared place flags, one
# per line.
_porto_place_flags() {
  if [[ -n "$PORTO_PLACE" ]]; then
    printf '%s\n' -P "$PORTO_PLACE"
  fi
}

engine_preflight() {
  local pod="$1"
  local -a place
  mapfile -t place < <(_porto_place_flags)

  riid_engine_exec "$pod" env "PORTO_SOCK=$PORTO_SOCKET" sh -ec '
    command -v portoctl >/dev/null || { echo "portoctl not found in pod" >&2; exit 1; }
    [ -S "$PORTO_SOCK" ] || { echo "porto socket not mounted: $PORTO_SOCK" >&2; exit 1; }
  ' || return 1

  # docker_images_support is off by default, and then docker-pull fails on the
  # very first image, in the middle of a measurement. Cheaper to learn it here.
  if ! riid_engine_exec "$pod" portoctl docker-images "${place[@]}" >/dev/null 2>&1; then
    echo "porto: docker images support is off in pod=$pod" >&2
    echo "  add to /etc/portod.conf: container { docker_images_support: true } and restart porto" >&2
    return 1
  fi
}

# Porto cannot complete a short reference: it has neither
# unqualified-search-registries like podman nor anything similar.
engine_ref() {
  local repo="$1" tag="$2" host
  host="$(riid_registry_pull_host)"
  if [[ -z "$host" ]]; then
    echo "porto: registry host is empty, set REGISTRY_PULL_HOST (portoctl docker-pull needs a fully qualified ref)" >&2
    return 2
  fi
  printf '%s/%s:%s\n' "${host%/}" "$repo" "$tag"
}

engine_pull() {
  local pod="$1" ref="$2"
  local -a place args
  mapfile -t place < <(_porto_place_flags)

  args=(portoctl docker-pull "${place[@]}")
  if [[ -n "$PORTO_PLATFORM" ]]; then
    args+=(-T "$PORTO_PLATFORM")
  fi

  # docker-pull prints the image id — harmless for the measurement, but it has
  # no place in the TSV.
  if [[ -n "${DOCKER_TOKEN:-}" ]]; then
    riid_engine_exec "$pod" env "DOCKER_TOKEN=$DOCKER_TOKEN" "${args[@]}" "$ref" >/dev/null
  else
    riid_engine_exec "$pod" "${args[@]}" "$ref" >/dev/null
  fi
}

# dfinit does not support Porto: its ContainerRuntimeConfig covers containerd,
# docker, podman and cri-o, and Porto has no mirror configuration at all — the
# registry comes from the reference itself. So the arm does not degrade into a
# plain pull, it fails honestly: by the AGENT-99 matrix Porto has only
# RIID => Porto and bare Porto.
_porto_no_dfinit() {
  echo "porto: dfinit does not support this engine (no backend in dragonfly-client-init, and Porto has no mirror config)" >&2
  echo "  per AGENT-99 Porto has only two arms: BACKEND=riid and BACKEND=bare" >&2
  return 2
}

engine_pull_mirrored() { _porto_no_dfinit; }
engine_mirror_check() { _porto_no_dfinit; }

# docker-images prints an "ID           NAME" header and one line per tag; the
# tag is what gets removed, because docker-rmi takes an image name.
engine_clear_cache() {
  local pod="$1"
  riid_engine_exec "$pod" env "PORTO_PLACE=$PORTO_PLACE" sh -ec '
    if [ -n "$PORTO_PLACE" ]; then set -- -P "$PORTO_PLACE"; else set --; fi
    portoctl docker-images "$@" | awk "NR > 1 && NF >= 2 { print \$2 }" | while read -r img; do
      [ -n "$img" ] || continue
      portoctl docker-rmi "$@" "$img" >/dev/null
    done
  '
}
