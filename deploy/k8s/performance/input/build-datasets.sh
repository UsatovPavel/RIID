#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"

INPUT_TSV="${1:-$REPO_ROOT/deploy/k8s/Selectel/registry/presented_images_list_sizes.tsv}"
OUT_A="${2:-$SCRIPT_DIR/dataset_a.tsv}"
OUT_B="${3:-$SCRIPT_DIR/dataset_b.tsv}"
ONE_GIB=1073741824

if [[ ! -f "$INPUT_TSV" ]]; then
  echo "Input TSV not found: $INPUT_TSV" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT_A")" "$(dirname "$OUT_B")"

awk -F '\t' -v OFS='\t' -v out_a="$OUT_A" -v out_b="$OUT_B" -v one_gib="$ONE_GIB" '
  NR == 1 {
    print $0 > out_a
    print $0 > out_b
    next
  }
  NF < 4 { next }
  {
    size_bytes = $3 + 0
    if (size_bytes >= one_gib) {
      print $0 >> out_b
    } else {
      print $0 >> out_a
    }
  }
' "$INPUT_TSV"

echo "Wrote dataset A: $OUT_A"
echo "Wrote dataset B: $OUT_B"
