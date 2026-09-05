#!/usr/bin/env bash
# Sums the real compressed size of a dataset straight from the registry's
# manifests. The checked-in dataset_a.tsv cannot be used for this: nine of its
# size cells are zero and it sums to 3.68 GiB against a true 11.46 GiB, which
# has already produced one wrong egress ratio.
#
# Every "x dataset" figure in Summary_bench.md divides by this number, so the
# zstd arms need their own - a zstd copy of the same images is a different size.
#
#   ./dataset-size.sh              # the gzip dataset (prefix riid/)
#   PREFIX=riid-zstd ./dataset-size.sh
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
      | grep -oE '\"size\":[0-9]+' | cut -d: -f2 \
      | awk -v r=\"\$r\" -v t=\"\$tag\" '{s+=\$1} END{printf \"%s\t%s\t%d\n\", r, t, s}'
  done
" | tee /dev/stderr | awk -F'\t' '
  {n++; total+=$3}
  END{printf "\n%d images, %d bytes = %.2f GiB\n", n, total, total/1073741824}'
