#!/bin/bash
# AGENT-95: does --discard-unpacked-layers stay correct on RIID's paths?
# V1: image still runnable after the blobs are gone
# V2: export stops working - the known price, documented rather than discovered later
# V3: prefix import + discard - a prefix tar carries only the layers it adds and
#     resolves the rest from the content store, which is exactly what GC empties
set -u
LAB="${LAB:-/home/ai/lab}"
LAYOUTS="$LAB/layouts"
PREFIXES="$LAB/prefixes"
IMG=library/python:3.13-slim-bookworm

wipe() {
    systemctl stop containerd >/dev/null 2>&1
    rm -rf /var/lib/containerd
    systemctl start containerd >/dev/null 2>&1
    for _ in $(seq 1 40); do ctr version >/dev/null 2>&1 && return; sleep 0.25; done
}

imp() { local d="$1"; shift; tar -cf - -C "$d" . | ctr images import "$@" - >/dev/null 2>&1; }

echo "=== V1/V2: whole import with --local --discard-unpacked-layers"
wipe
imp "$LAYOUTS/library_python_3.13-slim-bookworm" --local --discard-unpacked-layers
echo "images: $(ctr images ls -q | tr '\n' ' ')"
if ctr run --rm --snapshotter overlayfs "$IMG" v1check python3 -c 'print("RAN-OK")' 2>&1 | grep -q RAN-OK; then
    echo "V1 run-after-discard: OK"
else
    echo "V1 run-after-discard: FAILED"
    ctr run --rm --snapshotter overlayfs "$IMG" v1check2 python3 -c 'print(1)' 2>&1 | tail -2
fi
if ctr images export /tmp/v2.tar "$IMG" >/dev/null 2>&1; then
    echo "V2 export-after-discard: OK (blobs still there?)"
else
    echo "V2 export-after-discard: FAILED as expected -> $(ctr images export /tmp/v2.tar "$IMG" 2>&1 | tail -1)"
fi
rm -f /tmp/v2.tar

echo "=== V3: prefix sequence, every step with --local --discard-unpacked-layers"
wipe
d="$PREFIXES/library_python_3.13-slim-bookworm"
ok=yes
for p in "$d"/p*; do
    if ! imp "$p" --local --discard-unpacked-layers; then
        echo "  prefix $(basename "$p") FAILED"
        ok=no
    fi
done
if imp "$d/full" --local --discard-unpacked-layers; then
    echo "  final import: OK"
else
    echo "  final import: FAILED"
    ok=no
    tar -cf - -C "$d/full" . | ctr images import --local --discard-unpacked-layers - 2>&1 | tail -2
fi
echo "V3 prefix+discard: $ok  images: $(ctr images ls -q | tr '\n' ' ')"
if [ "$ok" = yes ]; then
    ctr run --rm --snapshotter overlayfs "$IMG" v3check python3 -c 'print("RAN-OK")' 2>&1 | tail -1
fi

echo "=== V4: prefix sequence with --no-unpack on the intermediates only"
wipe
ok=yes
for p in "$d"/p*; do
    imp "$p" --no-unpack || { echo "  prefix $(basename "$p") FAILED"; ok=no; }
done
imp "$d/full" || { echo "  final import FAILED"; ok=no; }
echo "V4 prefix+no-unpack: $ok images: $(ctr images ls -q | tr '\n' ' ')"
[ "$ok" = yes ] && ctr run --rm --snapshotter overlayfs "$IMG" v4check python3 -c 'print("RAN-OK")' 2>&1 | tail -1
wipe
echo VERIFYDONE
