#!/usr/bin/env bash
# dfget wrapper for multi-config (3 peers: dfdaemon1, dfdaemon2, dfdaemon3).
# All peers share host /tmp, so use unique output path per invocation to avoid collisions.
set -euo pipefail

container="${DFGET_CONTAINER:-dfdaemon1}"
container_out="/tmp/dfget-out-$$.bin"
host_out=""
direct_mount="${DFGET_DIRECT_MOUNT:-}"

args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -O)
      host_out="$2"
      if [[ -n "$direct_mount" ]]; then
        container_out="$host_out"
      fi
      args+=("-O" "$container_out")
      shift 2
      ;;
    *)
      args+=("$1")
      shift
      ;;
  esac
done

docker exec "$container" sh -lc '
if [ -x /usr/local/bin/dfget ]; then
  exec /usr/local/bin/dfget "$@"
fi
exec /opt/dragonfly/bin/dfget "$@"
' _ "${args[@]}"

if [[ -n "$host_out" && -z "$direct_mount" ]]; then
  docker cp "${container}:${container_out}" "$host_out"
fi
