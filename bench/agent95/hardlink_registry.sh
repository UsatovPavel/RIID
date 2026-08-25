#!/bin/bash
# Control for AGENT-95 / 1.1: same knob, but pulling from the registry instead of a
# local OCI layout. Store size is the criterion; time is meaningless here (network).
set -u
STORE_ROOT="$HOME/.cache/riid-hlbench"
GRAPH="$STORE_ROOT/graph"; RUNROOT="/run/user/$(id -u)/riid-hlbench"
CONF="$STORE_ROOT/storage.conf"; export CONTAINERS_STORAGE_CONF="$CONF"
IMAGES=(docker.io/library/python:3.11-slim-bookworm docker.io/library/node:20-slim docker.io/library/ruby:3.3-slim)
mkdir -p "$STORE_ROOT"
write_conf() { cat > "$CONF" <<EOF
[storage]
driver = "overlay"
runroot = "$RUNROOT"
graphroot = "$GRAPH"

[storage.options.pull_options]
enable_partial_images = "$3"
convert_images = "$2"
use_hard_links = "$1"
EOF
}
wipe() { podman system reset -f >/dev/null 2>&1; rm -rf "$GRAPH" "$RUNROOT"; mkdir -p "$GRAPH" "$RUNROOT"; }
arm() { # label hard convert partial
  write_conf "$2" "$3" "$4"; wipe
  local t0 t1
  t0=$(date +%s%N)
  for i in "${IMAGES[@]}"; do podman pull -q "$i" >/dev/null 2>&1 || echo "  FAIL $i" >&2; done
  t1=$(date +%s%N)
  sync
  echo "ARM $1 hard=$2 convert=$3 partial=$4 ms=$(( (t1-t0)/1000000 )) real=$(du -sb "$GRAPH" 2>/dev/null | cut -f1) apparent=$(du -sb --count-links "$GRAPH" 2>/dev/null | cut -f1)"
}
arm E false false false
arm F true  false false
arm G true  true  true
wipe; rm -rf "$STORE_ROOT" "$RUNROOT"; echo REGDONE
