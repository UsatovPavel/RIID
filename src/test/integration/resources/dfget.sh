#!/usr/bin/env bash
set -euo pipefail

container="${DFGET_CONTAINER:-dfdaemon1}"
container_out="/tmp/dfget-out.bin"
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

docker exec "$container" /opt/dragonfly/bin/dfget "${args[@]}"

if [[ -n "$host_out" && -z "$direct_mount" ]]; then
  docker cp "${container}:${container_out}" "$host_out"
fi
