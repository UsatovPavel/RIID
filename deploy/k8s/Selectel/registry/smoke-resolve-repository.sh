#!/usr/bin/env bash
# Print RIID POST /pull "repository" string: Docker Hub path by default, or Selectel path from performance-registry-smoke-map.tsv.
#
# Usage: smoke-resolve-repository.sh <docker_hub_repository>
# Env:
#   SMOKE_REGISTRY_TARGET — dockerhub | selectel (default: dockerhub)
#   SMOKE_REPOSITORY_MAP_FILE — default: same dir as this script / performance-registry-smoke-map.tsv
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAP="${SMOKE_REPOSITORY_MAP_FILE:-$SCRIPT_DIR/performance-registry-smoke-map.tsv}"
TARGET="${SMOKE_REGISTRY_TARGET:-dockerhub}"
HUB_REPO="${1:?usage: $0 <docker_hub_repository e.g. library/jobber>}"

if [[ "$TARGET" != "selectel" ]]; then
  printf '%s\n' "$HUB_REPO"
  exit 0
fi

[[ -f "$MAP" ]] || {
  echo "$0: selectel: missing map $MAP (run: make init-performance-registry-images)" >&2
  exit 1
}

line="$(awk -F '\t' -v k="$HUB_REPO" '!/^#/ && NF >= 2 && $1 == k { print $2; exit }' "$MAP")"
[[ -n "$line" ]] || {
  echo "$0: selectel: no row for \"$HUB_REPO\" in $MAP" >&2
  exit 1
}
printf '%s\n' "$line"
