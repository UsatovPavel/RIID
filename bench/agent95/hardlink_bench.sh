#!/bin/bash
# AGENT-95 / 1.1: podman pull_options.use_hard_links A/B.
# Imports only - blobs are already on disk, so no network in the measured section.
# Runs against an isolated store (CONTAINERS_STORAGE_CONF), never the host's own.
set -u
LAB="$(cd "$(dirname "$0")" && pwd)"
STORE_ROOT="${STORE_ROOT:-$HOME/.cache/riid-hlbench}"
GRAPH="$STORE_ROOT/graph"
RUNROOT="/run/user/$(id -u)/riid-hlbench"
CONF="$STORE_ROOT/storage.conf"
ROUNDS="${ROUNDS:-3}"
export CONTAINERS_STORAGE_CONF="$CONF"

mkdir -p "$STORE_ROOT"

write_conf() { # $1 = true|false
    cat > "$CONF" <<EOF
[storage]
driver = "overlay"
runroot = "$RUNROOT"
graphroot = "$GRAPH"

[storage.options.pull_options]
use_hard_links = "$1"
EOF
}

wipe() {
    podman system reset -f >/dev/null 2>&1
    rm -rf "$GRAPH" "$RUNROOT"
    mkdir -p "$GRAPH" "$RUNROOT"
}

refof() {
    python3 -c "import json,sys;print(json.load(open(sys.argv[1]+'/index.json'))['manifests'][0]['annotations']['org.opencontainers.image.ref.name'])" "$1"
}

run_arm() { # $1 = label, $2 = use_hard_links
    write_conf "$2"
    wipe
    local total=0 d ref t0 t1 ms
    for d in "$LAB"/layouts/*; do
        ref=$(refof "$d")
        t0=$(date +%s%N)
        podman pull -q "oci:$d:$ref" >/dev/null 2>&1 || echo "    FAIL $ref" >&2
        t1=$(date +%s%N)
        ms=$(((t1 - t0) / 1000000))
        total=$((total + ms))
        printf "    %-44s %6d ms\n" "$ref" "$ms"
    done
    sync
    local real apparent
    real=$(du -sb "$GRAPH" | cut -f1)          # hardlinked inode counted once
    apparent=$(du -sb --count-links "$GRAPH" | cut -f1) # every link counted
    echo "ARM $1 use_hard_links=$2 total_ms=$total real_bytes=$real apparent_bytes=$apparent"
}

echo "store=$GRAPH conf=$CONF rounds=$ROUNDS images=$(ls -1 "$LAB"/layouts | wc -l)"
for r in $(seq 1 "$ROUNDS"); do
    echo "=== round $r"
    if ((r % 2 == 1)); then # alternate order so any drift cancels
        run_arm "A" "false"
        run_arm "B" "true"
    else
        run_arm "B" "true"
        run_arm "A" "false"
    fi
done
wipe
rm -rf "$STORE_ROOT" "$RUNROOT"
echo "BENCHDONE (isolated store removed; host store and config untouched)"
