#!/usr/bin/env bash
# Emits a dataset TSV for a registry prefix, with sizes read from the manifests.
#
# The checked-in dataset_a.tsv cannot be reused for the zstd arms: its sizes are
# the gzip ones (and nine of them are zero), while a zstd copy of the same images
# is a different size - and every "x dataset" ratio divides by that size.
#
#   PREFIX=riid-zstd ./make-dataset-tsv.sh > deploy/k8s/performance/input/dataset_a_zstd.tsv
set -euo pipefail
STAND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
set -a; . "${STAND_DIR}/stand.env"; set +a
. "${STAND_DIR}/lib.sh"

REGISTRY="${REGISTRY:-10.96.5.146:5000}"
PREFIX="${PREFIX:-riid}"
WORKER="${WORKER:-aibox2}"

printf 'repository\ttag\tsize_bytes\tsize_human\n'
node_run "$WORKER" "
  repos=\$(curl -sS -m 30 http://${REGISTRY}/v2/_catalog?n=200 \
          | tr ',' '\n' | grep -oE '${PREFIX}/[A-Za-z0-9._/-]+')
  for r in \$repos; do
    tag=\$(curl -sS -m 30 http://${REGISTRY}/v2/\$r/tags/list \
          | grep -oE '\"tags\":\[\"[^\"]+' | cut -d'\"' -f4)
    [ -n \"\$tag\" ] || continue
    sz=\$(curl -sS -m 60 \
      -H 'Accept: application/vnd.oci.image.manifest.v1+json' \
      -H 'Accept: application/vnd.docker.distribution.manifest.v2+json' \
      http://${REGISTRY}/v2/\$r/manifests/\$tag \
      | grep -oE '\"size\":[0-9]+' | cut -d: -f2 | awk '{s+=\$1} END{print s+0}')
    printf '%s\t%s\t%s\n' \"\$r\" \"\$tag\" \"\$sz\"
  done" \
| awk -F'\t' '{printf "%s\t%s\t%s\t%.1f MiB\n", $1, $2, $3, $3/1048576}'
