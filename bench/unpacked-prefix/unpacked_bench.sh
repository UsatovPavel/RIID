#!/bin/bash
# AGENT-95 item 2.2 on containerd. Runs inside Porto_VM, as root.
# Blobs are already on disk: no network in any measured section, so what is timed
# is only the handoff to the engine.
set -u
LAB="${LAB:-/home/ai/lab}"
OUT="${OUT:-$LAB/unpacked}"
ROUNDS="${ROUNDS:-3}"
ARMS="${ARMS:-gzip tar tar-fullall}"
IMAGES="${IMAGES:-library_python_3.11-slim-bookworm library_node_20-slim library_ruby_3.3-slim}"
CONTENT=/var/lib/containerd/io.containerd.content.v1.content
SNAP=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs

wipe() {
    systemctl stop containerd >/dev/null 2>&1
    rm -rf /var/lib/containerd
    systemctl start containerd >/dev/null 2>&1
    for _ in $(seq 1 40); do
        ctr version >/dev/null 2>&1 && return
        sleep 0.25
    done
    echo "containerd did not come back" >&2
}

sizes() {
    sync
    echo "content=$(du -sb $CONTENT 2>/dev/null | cut -f1)" \
        "snap=$(du -sb $SNAP 2>/dev/null | cut -f1)" \
        "total=$(du -sb /var/lib/containerd 2>/dev/null | cut -f1)"
}

imp() { # $1 = layout dir; prints ms
    local d="$1" t0 t1
    t0=$(date +%s%N)
    if ! tar -cf - -C "$d" . | ctr images import - >>"$OUT/ctr.err" 2>&1; then
        echo "  FAIL $d" >&2
    fi
    t1=$(date +%s%N)
    echo $(((t1 - t0) / 1000000))
}

# An import that "succeeds" while leaving the content store short of a layer
# still has to run; `images check` reports the same thing from the other side.
verify() {
    local ref id="v$RANDOM" run check
    ref=$(ctr images ls -q 2>/dev/null | grep -v "^riid-prefix-" | head -1)
    [ -n "$ref" ] || { echo "run=NO_IMAGE check=-"; return; }
    if ctr run --rm "$ref" "$id" /bin/true >>"$OUT/ctr.err" 2>&1; then
        run=OK
    else
        run=FAIL
    fi
    check=$(ctr images check "name==$ref" 2>/dev/null | awk 'NR==2 {print $4}')
    echo "run=$run check=${check:-none}"
}

# One image through the whole prefix sequence, then the final import, on a node
# wiped beforehand.
measure() { # $1 = label, $2 = built layout dir
    local label="$1" img="$2" pre=0 fin p ms
    wipe
    for p in "$img"/p*; do
        [ -d "$p" ] || continue
        ms=$(imp "$p")
        pre=$((pre + ms))
    done
    fin=$(imp "$img/full")
    # RIID drops the intermediates in finish(); the store must be measured the
    # same way, the prefix images being what keeps their blobs off the GC.
    ctr images ls -q 2>/dev/null | grep "^riid-prefix-" | xargs -r ctr images rm >/dev/null 2>&1
    echo "$label prefix_ms=$pre final_ms=$fin total_ms=$((pre + fin)) $(verify) $(sizes)"
}

mkdir -p "$OUT"
: >"$OUT/ctr.err"

# Build every arm once: layout construction is deterministic and must not land
# inside a timed section. unpack_s is what RIID itself would pay for the gunzip.
for img in $IMAGES; do
    for a in $ARMS; do
        rm -rf "$OUT/$img.$a"
        python3 "$LAB/unpacked_layouts.py" "$LAB/layouts/$img" "$OUT/$img.$a" "s$RANDOM" "$a" || exit 1
    done
done

for r in $(seq 1 "$ROUNDS"); do
    for img in $IMAGES; do
        for a in $ARMS; do
            measure "r=$r img=$img arm=$a" "$OUT/$img.$a"
        done
    done
done
