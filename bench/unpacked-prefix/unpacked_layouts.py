#!/usr/bin/env python3
"""AGENT-95 item 2.2: hand containerd already-decompressed layers.

Builds the layouts ContainerdRuntimeAdapter emits (LayerScope.ADDED_ONLY, config
cut to `count` diff_ids, history dropped), in three arms:

  gzip        what RIID does today - every prefix carries the gzip blob
  tar         2.2 as the ticket sketches it - prefixes carry the decompressed
              layer, descriptor rewritten to (diff_id, uncompressed size,
              ...image.layer.v1.tar); the final layout is the untouched manifest
              with ADDED_ONLY, exactly as today
  tar-fullall same prefixes, but the final layout carries every gzip blob
  tar-final   everything uncompressed, the final layout included - not what the
              ticket proposes (it keeps the final image original), but it bounds
              the win by showing what the original final layout costs
  tar-thin    uncompressed prefixes, untouched final manifest, but only the last
              layer's blob in the final layout - tests whether podman really
              needs every layer present the way LayerScope.ALL assumes

The scope is the adapter's LayerScope: containerd has a content store and gets
only what each step adds, podman needs every layer in every layout.

usage: unpacked_layouts.py <src-layout> <out-dir> <session-id> <arm> [scope] [engine]
       scope:  added (default) | all
       engine: containerd (default) | podman - only names the images, but podman
               qualifies an unqualified name with localhost/ and the pull
               reference has to match the annotation, so it is not cosmetic
"""
import gzip
import hashlib
import json
import os
import shutil
import sys
import time

PREFIX_REPO = "riid-prefix-"
TAR_MEDIA_TYPE = "application/vnd.oci.image.layer.v1.tar"
CHUNK = 1 << 20


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


def gunzip_to_store(src_blob, store):
    """Decompresses one layer into `store`, named by its own sha256. Returns
    (digest, size, seconds) - the digest must come out equal to the diff_id."""
    t0 = time.monotonic()
    h = hashlib.sha256()
    size = 0
    tmp = os.path.join(store, "incoming")
    with gzip.open(src_blob, "rb") as fin, open(tmp, "wb") as fout:
        while True:
            chunk = fin.read(CHUNK)
            if not chunk:
                break
            h.update(chunk)
            size += len(chunk)
            fout.write(chunk)
    digest = h.hexdigest()
    os.replace(tmp, os.path.join(store, digest))
    return digest, size, time.monotonic() - t0


def main():
    src, out, session, arm = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    scope = sys.argv[5] if len(sys.argv) > 5 else "added"
    engine = sys.argv[6] if len(sys.argv) > 6 else "containerd"
    if arm not in ("gzip", "tar", "tar-fullall", "tar-final", "tar-thin"):
        sys.exit("unknown arm: " + arm)
    if scope not in ("added", "all"):
        sys.exit("unknown scope: " + scope)
    if engine not in ("containerd", "podman"):
        sys.exit("unknown engine: " + engine)
    qualify = "localhost/" if engine == "podman" else ""
    idx = json.load(open(os.path.join(src, "index.json")))
    ref = idx["manifests"][0]["annotations"]["org.opencontainers.image.ref.name"]
    manifest = json.load(open(blob_path(src, idx["manifests"][0]["digest"])))
    config_bytes = open(blob_path(src, manifest["config"]["digest"]), "rb").read()
    diff_ids = json.loads(config_bytes)["rootfs"]["diff_ids"]
    layers = manifest["layers"]
    n = len(layers)
    shutil.rmtree(out, ignore_errors=True)

    # Decompressed blobs live once, next to the layouts, and are hard linked in -
    # the same trick PrefixImportLayouts uses for the gzip ones.
    store = os.path.join(out, "unpacked")
    os.makedirs(store, exist_ok=True)
    step_layers = layers
    unpack_s = 0.0
    if arm != "gzip":  # every tar-* arm hands the prefixes over decompressed
        step_layers = []
        for i, layer in enumerate(layers):
            digest, size, secs = gunzip_to_store(blob_path(src, layer["digest"]), store)
            unpack_s += secs
            if "sha256:" + digest != diff_ids[i]:
                sys.exit("layer %d: sha256(tar)=%s but diff_id=%s" % (i, digest, diff_ids[i]))
            step_layers.append({"mediaType": TAR_MEDIA_TYPE,
                                "digest": diff_ids[i], "size": size})

    def link(src_file, layout, digest):
        dst = blob_path(layout, digest)
        if not os.path.exists(dst):
            os.link(src_file, dst)

    def link_step_layers(layout, lo, hi):
        if scope == "all":
            lo = 0
        for i in range(lo, hi):
            if arm == "gzip":
                link(blob_path(src, layers[i]["digest"]), layout, layers[i]["digest"])
            else:
                link(os.path.join(store, diff_ids[i].split(":")[1]), layout, diff_ids[i])

    sent = 0
    for count in range(1, n):
        layout = new_layout(out, "p%d" % count)
        link_step_layers(layout, sent, count)
        cfg = json.loads(config_bytes)
        # diff_ids are the uncompressed digests either way: the cut is arm-independent
        cfg["rootfs"]["diff_ids"] = cfg["rootfs"]["diff_ids"][:count]
        cfg.pop("history", None)
        cfg_bytes = json.dumps(cfg, separators=(",", ":")).encode()
        cfg_digest = write_blob(layout, cfg_bytes)
        pm = {
            "schemaVersion": 2,
            "mediaType": manifest["mediaType"],
            "config": {"mediaType": manifest["config"]["mediaType"],
                       "digest": "sha256:" + cfg_digest, "size": len(cfg_bytes)},
            "layers": step_layers[:count],
        }
        write_index(layout, json.dumps(pm, separators=(",", ":")).encode(),
                    "%s%s%s:%d" % (qualify, PREFIX_REPO, session, count))
        sent = count

    # The final layout is the untouched manifest in every arm but tar-final -
    # that is the property fullLayout() promises. Only which blobs it carries
    # differs. The config is untouched everywhere, diff_ids being compression
    # independent, so the image id an engine derives from it does not move.
    layout = new_layout(out, "full")
    first = 0 if (arm == "tar-fullall" or scope == "all") else sent
    if arm == "tar-thin":
        first = n - 1
    if arm == "tar-final":
        for i in range(first, n):
            link(os.path.join(store, diff_ids[i].split(":")[1]), layout, diff_ids[i])
        final_manifest = dict(manifest, layers=step_layers)
    else:
        for i in range(first, n):
            link(blob_path(src, layers[i]["digest"]), layout, layers[i]["digest"])
        final_manifest = manifest
    write_blob(layout, config_bytes)
    write_index(layout, json.dumps(final_manifest, separators=(",", ":")).encode(), qualify + ref)
    print("%s arm=%s scope=%s engine=%s prefixes=%d unpack_s=%.2f ref=%s"
          % (out, arm, scope, engine, n - 1, unpack_s, qualify + ref))


main()
