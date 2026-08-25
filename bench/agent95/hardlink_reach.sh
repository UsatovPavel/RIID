#!/bin/bash
# Diagnostic for AGENT-95 / 1.1: is use_hard_links reachable at all for plain gzip layers?
# C: convert_images=true + use_hard_links=true  -> chunked differ runs, hard links allowed
# D: convert_images=true + use_hard_links=false -> chunked differ runs, hard links denied
# If C == D == A(plain), the knob never fires. If C < D, hard linking lives on the chunked path.
set -u
LAB="$(cd "$(dirname "$0")" && pwd)"
STORE_ROOT="${STORE_ROOT:-$HOME/.cache/riid-hlbench}"
GRAPH="$STORE_ROOT/graph"
RUNROOT="/run/user/$(id -u)/riid-hlbench"
CONF="$STORE_ROOT/storage.conf"
export CONTAINERS_STORAGE_CONF="$CONF"
mkdir -p "$STORE_ROOT"

write_conf() { # $1 = use_hard_links, $2 = convert_images
    cat > "$CONF" <<EOF
[storage]
driver = "overlay"
runroot = "$RUNROOT"
graphroot = "$GRAPH"

[storage.options.pull_options]
enable_partial_images = "true"
convert_images = "$2"
use_hard_links = "$1"
EOF
}

wipe() {
    podman system reset -f >/dev/null 2>&1
    rm -rf "$GRAPH" "$RUNROOT"
    mkdir -p "$GRAPH" "$RUNROOT"
}

run_arm() { # $1 = label, $2 = use_hard_links, $3 = convert_images
    write_conf "$2" "$3"
    wipe
    local total=0 d ref t0 t1 ms
    for d in "$LAB"/layouts/*; do
        ref=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]+'/index.json'))['manifests'][0]['annotations']['org.opencontainers.image.ref.name'])" "$d")
        t0=$(date +%s%N)
        podman pull -q "oci:$d:$ref" >/dev/null 2>&1 || echo "    FAIL $ref" >&2
        t1=$(date +%s%N)
        ms=$(((t1 - t0) / 1000000))
        total=$((total + ms))
    done
    sync
    local real apparent
    real=$(du -sb "$GRAPH" 2>/dev/null | cut -f1)
    apparent=$(du -sb --count-links "$GRAPH" 2>/dev/null | cut -f1)
    echo "ARM $1 use_hard_links=$2 convert_images=$3 total_ms=$total real_bytes=$real apparent_bytes=$apparent"
}

run_arm "C" "true" "true"
run_arm "D" "false" "true"
wipe
rm -rf "$STORE_ROOT" "$RUNROOT"
echo "DIAGDONE"
