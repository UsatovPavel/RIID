#!/usr/bin/env bash
set -euo pipefail

container="dfdaemon1"
container_out="/tmp/dfget-out.bin"
host_out=""

args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -O)
      host_out="$2"
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

if [[ -n "$host_out" ]]; then
  docker cp "${container}:${container_out}" "$host_out"
fi
