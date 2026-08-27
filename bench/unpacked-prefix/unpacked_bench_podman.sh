#!/bin/bash
# AGENT-95 item 2.2 on podman, the baseline engine. Rootless, against an isolated
# store (CONTAINERS_STORAGE_CONF) - the host's own store is never touched.
# Blobs are already on disk: no network in any measured section.
set -u
LAB="${LAB:-$HOME/.cache/riid-unpack22}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$LAB/built}"
ROUNDS="${ROUNDS:-3}"
ARMS="${ARMS:-gzip tar tar-final}"
IMAGES="${IMAGES:-library_python_3.11-slim-bookworm library_node_20-slim library_ruby_3.3-slim}"
STORE_ROOT="$LAB/store"
GRAPH="$STORE_ROOT/graph"
RUNROOT="/run/user/$(id -u)/riid-unpack22"
CONF="$STORE_ROOT/storage.conf"
export CONTAINERS_STORAGE_CONF="$CONF"

mkdir -p "$STORE_ROOT" "$OUT"
cat >"$CONF" <<EOF
[storage]
driver = "overlay"
runroot = "$RUNROOT"
graphroot = "$GRAPH"
EOF

wipe() {
    podman system reset -f >/dev/null 2>&1
    rm -rf "$GRAPH" "$RUNROOT"
    mkdir -p "$GRAPH" "$RUNROOT"
}

refof() { # the pull reference has to be the ref.name the layout declares
    python3 -c "import json,sys;print(json.load(open(sys.argv[1]+'/index.json'))['manifests'][0]['annotations']['org.opencontainers.image.ref.name'])" "$1"
}

pull() { # $1 = layout dir; prints ms
    local d="$1" t0 t1
    t0=$(date +%s%N)
    if ! podman pull -q "oci:$d:$(refof "$d")" >>"$OUT/podman.err" 2>&1; then
        echo "  FAIL $d" >&2
    fi
    t1=$(date +%s%N)
    echo $(((t1 - t0) / 1000000))
}

# The image has to run, not just import: podman keys a layer by chain-id, so a
# prefix handed over uncompressed must still satisfy the final gzip manifest.
verify() { # $1 = final layout dir
    local ref
    ref=$(refof "$1")
    if podman run --rm "$ref" /bin/true >>"$OUT/podman.err" 2>&1; then
        echo "run=OK"
    else
        echo "run=FAIL"
    fi
}

measure() { # $1 = label, $2 = built layout dir
    local label="$1" img="$2" pre=0 fin p ms
    wipe
    for p in "$img"/p*; do
        [ -d "$p" ] || continue
        ms=$(pull "$p")
        pre=$((pre + ms))
    done
    fin=$(pull "$img/full")
    # The adapter drops the intermediates in finish(); the store is measured the
    # same way, an image record being what holds its layers.
    podman images -q --filter "reference=localhost/riid-prefix-*" 2>/dev/null \
        | xargs -r podman rmi -f >/dev/null 2>&1
    sync
    echo "$label prefix_ms=$pre final_ms=$fin total_ms=$((pre + fin)) $(verify "$img/full")" \
        "store=$(du -sb "$GRAPH" 2>/dev/null | cut -f1)"
}

: >"$OUT/podman.err"

# Built once: layout construction is deterministic and must stay out of a timed
# section. LayerScope.ALL, as PodmanRuntimeAdapter uses it.
for img in $IMAGES; do
    for a in $ARMS; do
        rm -rf "$OUT/$img.$a"
        python3 "$HERE/unpacked_layouts.py" "$LAB/layouts/$img" "$OUT/$img.$a" \
            "s$RANDOM" "$a" all podman || exit 1
    done
done

for r in $(seq 1 "$ROUNDS"); do
    for img in $IMAGES; do
        for a in $ARMS; do
            measure "r=$r img=$img arm=$a" "$OUT/$img.$a"
        done
    done
done
wipe
