#!/usr/bin/env python3
"""Download an image from Docker Hub into a local OCI layout (gzipped blobs, as RIID gets them)."""
import hashlib, json, os, sys, urllib.request

REG = "https://registry-1.docker.io"
AUTH = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:%s:pull"
ACCEPT = ",".join([
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
])

def get(url, token, accept=None, binary=False):
    req = urllib.request.Request(url)
    req.add_header("Authorization", "Bearer " + token)
    if accept:
        req.add_header("Accept", accept)
    with urllib.request.urlopen(req) as r:
        return r.read() if binary else (r.read(), dict(r.headers))

def main(repo, tag, out):
    token = json.loads(urllib.request.urlopen(AUTH % repo).read())["token"]
    body, hdrs = get(f"{REG}/v2/{repo}/manifests/{tag}", token, ACCEPT)
    doc = json.loads(body)
    if "manifests" in doc:  # index -> pick linux/amd64
        d = next(m for m in doc["manifests"]
                 if m.get("platform", {}).get("architecture") == "amd64"
                 and m.get("platform", {}).get("os") == "linux")
        body, hdrs = get(f"{REG}/v2/{repo}/manifests/{d['digest']}", token, ACCEPT)
        doc = json.loads(body)
    blobs = os.path.join(out, "blobs", "sha256")
    os.makedirs(blobs, exist_ok=True)
    for desc in [doc["config"]] + doc["layers"]:
        hexd = desc["digest"].split(":")[1]
        dst = os.path.join(blobs, hexd)
        if os.path.exists(dst) and os.path.getsize(dst) == desc["size"]:
            continue
        data = get(f"{REG}/v2/{repo}/blobs/{desc['digest']}", token, binary=True)
        assert hashlib.sha256(data).hexdigest() == hexd, hexd
        with open(dst, "wb") as f:
            f.write(data)
        print(f"  blob {hexd[:12]} {len(data)/1048576:.1f} MiB", flush=True)
    mbytes = json.dumps(doc, separators=(",", ":")).encode()
    mdig = hashlib.sha256(mbytes).hexdigest()
    with open(os.path.join(blobs, mdig), "wb") as f:
        f.write(mbytes)
    with open(os.path.join(out, "oci-layout"), "w") as f:
        f.write('{"imageLayoutVersion":"1.0.0"}')
    index = {"schemaVersion": 2, "manifests": [{
        "mediaType": doc["mediaType"], "size": len(mbytes), "digest": "sha256:" + mdig,
        "annotations": {"org.opencontainers.image.ref.name": f"{repo}:{tag}"}}]}
    with open(os.path.join(out, "index.json"), "w") as f:
        json.dump(index, f)
    print(f"layout {out}: {len(doc['layers'])} layers, "
          f"{sum(l['size'] for l in doc['layers'])/1048576:.0f} MiB")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
