#!/usr/bin/env bash
# Dumps every distinct blob (digest, size) of a dataset prefix, so the piece
# arithmetic can be done offline.
#
# Dragonfly sizes a piece as clamp(next_power_of_two(len/500), 4 MiB, 64 MiB)
# (dragonfly-client/src/resource/piece.rs), so any blob under 2 GiB is cut into
# 4 MiB pieces regardless of its size. Whether the gzip->zstd repack changed the
# piece count in any interesting way is therefore answerable from the manifests
# alone - no stand time needed.
set -euo pipefail
STAND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
set -a; . "${STAND_DIR}/stand.env"; set +a
. "${STAND_DIR}/lib.sh"
REGISTRY="${REGISTRY:-10.96.5.146:5000}"
PREFIX="${PREFIX:-riid}"
WORKER="${WORKER:-aibox2}"

node_run "$WORKER" "
  set -e
  repos=\$(curl -sS -m 30 http://${REGISTRY}/v2/_catalog?n=200 \
          | tr ',' '\n' | grep -oE '${PREFIX}/[A-Za-z0-9._/-]+')
  for r in \$repos; do
    tag=\$(curl -sS -m 30 http://${REGISTRY}/v2/\$r/tags/list \
          | grep -oE '\"tags\":\[\"[^\"]+' | cut -d'\"' -f4)
    [ -n \"\$tag\" ] || continue
    curl -sS -m 60 \
      -H 'Accept: application/vnd.oci.image.manifest.v1+json' \
      -H 'Accept: application/vnd.docker.distribution.manifest.v2+json' \
      http://${REGISTRY}/v2/\$r/manifests/\$tag \
      | tr -d '\n'; echo
  done
" | python3 -c '
import sys, json
# Field order inside a descriptor is not fixed - some manifests carry
# mediaType/size/digest, others mediaType/digest/size - so parse the JSON
# instead of pattern-matching the byte order.
seen = {}
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        m = json.loads(line)
    except ValueError:
        continue
    descs = list(m.get("layers") or [])
    if m.get("config"):
        descs.append(m["config"])
    for d in descs:
        if "digest" in d and "size" in d:
            seen[d["digest"]] = int(d["size"])
for dg, sz in sorted(seen.items()):
    print(f"{dg}\t{sz}")
'
