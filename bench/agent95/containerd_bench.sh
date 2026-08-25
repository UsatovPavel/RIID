#!/bin/bash
# AGENT-95 / 1.2-1.4 on containerd. Runs inside Porto_VM, as root.
# Blobs are already on disk: no network in any measured section.
set -u
LAB="${LAB:-/home/ai/lab}"
LAYOUTS="$LAB/layouts"
PREFIXES="$LAB/prefixes"
CONTENT=/var/lib/containerd/io.containerd.content.v1.content
SNAP_OVERLAY=/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs
SNAP_NATIVE=/var/lib/containerd/io.containerd.snapshotter.v1.native

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

sizes() { # prints "content=<b> snap=<b> total=<b>"
    sync
    echo "content=$(du -sb $CONTENT 2>/dev/null | cut -f1)" \
        "snapOverlay=$(du -sb $SNAP_OVERLAY 2>/dev/null | cut -f1)" \
        "snapNative=$(du -sb $SNAP_NATIVE 2>/dev/null | cut -f1)" \
        "total=$(du -sb /var/lib/containerd 2>/dev/null | cut -f1)"
}

imp() { # $1 = layout dir, rest = extra ctr flags; prints ms
    local d="$1"
    shift
    local t0 t1
    t0=$(date +%s%N)
    tar -cf - -C "$d" . | ctr images import "$@" - >/dev/null 2>>"$LAB/ctr.err" || echo "  FAIL $d" >&2
    t1=$(date +%s%N)
    echo $(((t1 - t0) / 1000000))
}

# ---------- 1.2 / 1.3: whole-image import, one flag at a time ----------
whole_arm() { # $1 = label, rest = ctr flags
    local label="$1"
    shift
    wipe
    local total=0 d ms
    for d in "$LAYOUTS"/*; do
        ms=$(imp "$d" "$@")
        total=$((total + ms))
    done
    echo "WHOLE $label flags='$*' total_ms=$total $(sizes)"
}

# ---------- 1.4: prefix sequence, --no-unpack on the intermediates ----------
prefix_arm() { # $1 = label, $2 = "yes" to pass --no-unpack on prefixes
    local label="$1" nounpack="$2"
    wipe
    local pre_total=0 fin_total=0 img p ms
    for img in "$PREFIXES"/*; do
        for p in "$img"/p*; do
            [ -d "$p" ] || continue
            if [ "$nounpack" = yes ]; then
                ms=$(imp "$p" --no-unpack)
            else
                ms=$(imp "$p")
            fi
            pre_total=$((pre_total + ms))
        done
        ms=$(imp "$img/full")
        fin_total=$((fin_total + ms))
    done
    echo "PREFIX $label no_unpack=$nounpack prefix_ms=$pre_total final_ms=$fin_total total_ms=$((pre_total + fin_total)) $(sizes)"
}

: >"$LAB/ctr.err"
case "${1:-all}" in
12)
    # ctr 2.2.1 refuses --discard-unpacked-layers without --local, so `local` is its
    # own arm: otherwise the discard effect and the cost of leaving the transfer
    # service would be measured as one number.
    whole_arm base
    whole_arm local --local
    whole_arm localdiscard --local --discard-unpacked-layers
    ;;
13)
    # through the transfer service only the host-configured snapshotter has an
    # unpack platform, so a non-default one needs --local; both arms get it, or the
    # snapshotter difference and the importer difference would be one number.
    whole_arm overlayfs --snapshotter overlayfs
    whole_arm local_overlayfs --local --snapshotter overlayfs
    whole_arm local_native --local --snapshotter native
    ;;
14)
    prefix_arm current no
    prefix_arm nounpack yes
    ;;
*)
    echo "usage: $0 12|13|14" >&2
    exit 2
    ;;
esac
