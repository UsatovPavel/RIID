#!/usr/bin/env python3
"""Builds the prefix layouts RIID hands containerd, so the CLI bench matches
ContainerdRuntimeAdapter: LayerScope.ADDED_ONLY, config cut to `count` diff_ids,
history dropped, real manifest/config for the final layout.

usage: prefix_layouts.py <src-layout> <out-dir> <session-id>
emits <out-dir>/p1 ... p(n-1), <out-dir>/full
"""
import hashlib
import json
import os
import shutil
import sys

PREFIX_REPO = "riid-prefix-"


def blob_path(layout, digest):
    return os.path.join(layout, "blobs", "sha256", digest.split(":")[1])


def write_blob(layout, data):
    d = hashlib.sha256(data).hexdigest()
    dst = os.path.join(layout, "blobs", "sha256", d)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "wb") as f:
        f.write(data)
    return d


def new_layout(out, name):
    layout = os.path.join(out, name)
    os.makedirs(os.path.join(layout, "blobs", "sha256"), exist_ok=True)
    with open(os.path.join(layout, "oci-layout"), "w") as f:
        json.dump({"imageLayoutVersion": "1.0.0"}, f)
    return layout


def write_index(layout, manifest_bytes, ref):
    d = write_blob(layout, manifest_bytes)
    idx = {
        "schemaVersion": 2,
        "manifests": [{
            "mediaType": "application/vnd.oci.image.manifest.v1+json",
            "size": len(manifest_bytes),
            "digest": "sha256:" + d,
            "annotations": {"org.opencontainers.image.ref.name": ref},
        }],
    }
    with open(os.path.join(layout, "index.json"), "w") as f:
        json.dump(idx, f)


def main():
    src, out, session = sys.argv[1], sys.argv[2], sys.argv[3]
    idx = json.load(open(os.path.join(src, "index.json")))
    ref = idx["manifests"][0]["annotations"]["org.opencontainers.image.ref.name"]
    manifest = json.load(open(blob_path(src, idx["manifests"][0]["digest"])))
    config_bytes = open(blob_path(src, manifest["config"]["digest"]), "rb").read()
    layers = manifest["layers"]
    n = len(layers)
    shutil.rmtree(out, ignore_errors=True)

    def link_layers(layout, lo, hi):  # ADDED_ONLY: only what this step adds
        for i in range(lo, hi):
            os.link(blob_path(src, layers[i]["digest"]), blob_path(layout, layers[i]["digest"]))

    sent = 0
    for count in range(1, n):
        layout = new_layout(out, "p%d" % count)
        link_layers(layout, sent, count)
        cfg = json.loads(config_bytes)
        cfg["rootfs"]["diff_ids"] = cfg["rootfs"]["diff_ids"][:count]
        cfg.pop("history", None)
        cfg_bytes = json.dumps(cfg, separators=(",", ":")).encode()
        cfg_digest = write_blob(layout, cfg_bytes)
        pm = {
            "schemaVersion": 2,
            "mediaType": manifest["mediaType"],
            "config": {"mediaType": manifest["config"]["mediaType"],
                       "digest": "sha256:" + cfg_digest, "size": len(cfg_bytes)},
            "layers": layers[:count],
        }
        write_index(layout, json.dumps(pm, separators=(",", ":")).encode(),
                    "%s%s:%d" % (PREFIX_REPO, session, count))
        sent = count

    layout = new_layout(out, "full")
    link_layers(layout, sent, n)
    write_blob(layout, config_bytes)
    write_index(layout, json.dumps(manifest, separators=(",", ":")).encode(), ref)
    print("%s prefixes=%d ref=%s" % (out, n - 1, ref))


main()
