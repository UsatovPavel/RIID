#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INPUT_TSV="${1:-$SCRIPT_DIR/../images_list.sizes.tsv}"
UNSUCCESSFUL_TXT="${2:-$SCRIPT_DIR/unsuccessfule_downloads.txt}"
OUTPUT_TSV="${3:-$SCRIPT_DIR/presented_images_list_sizes.tsv}"

if [[ ! -f "$INPUT_TSV" ]]; then
  echo "Input TSV not found: $INPUT_TSV" >&2
  exit 1
fi

if [[ ! -f "$UNSUCCESSFUL_TXT" ]]; then
  echo "Unsuccessful list not found: $UNSUCCESSFUL_TXT" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT_TSV")"

awk -F '\t' '
  NR==FNR {
    if ($0 ~ /^#/ || NF < 2) {
      next
    }
    key = $1 "\t" $2
    excluded[key] = 1
    next
  }
  FNR==1 {
    print $0
    next
  }
  {
    key = $1 "\t" $2
    if (!(key in excluded)) {
      print $0
    }
  }
' "$UNSUCCESSFUL_TXT" "$INPUT_TSV" >"$OUTPUT_TSV"

echo "Wrote: $OUTPUT_TSV"
