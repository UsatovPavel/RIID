#!/usr/bin/env bash
# Из presented_images_list_sizes.tsv (имена как на Docker Hub) строит dataset_a.tsv / dataset_b.tsv
# с колонкой repository в форме Selectel CR, совпадающей с init-performance-registry-images.sh:
#   <REG_REPO_PREFIX>/<push_repo>, где для selcloud.ru по умолчанию push_repo без префикса library/.
#
# Требует .env того же профиля, что и «make init-performance-registry-images»:
#   REGISTRY (= UUID или cr.selcloud.ru/<uuid>), опционально REGISTRY_LOGIN_HOST, REGISTRY_PUSH_REPO_STRIP_LIBRARY.
#
# Env:
#   PERF_REGISTRY_ENV_FILE — путь к .env (default: deploy/k8s/Selectel/.env)
#
# Аргументы: [INPUT_TSV] [OUT_DATASET_A.tsv] [OUT_DATASET_B.tsv]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"

INPUT_TSV="${1:-$REPO_ROOT/deploy/k8s/Selectel/registry/presented_images_list_sizes.tsv}"
OUT_A="${2:-$SCRIPT_DIR/dataset_a.tsv}"
OUT_B="${3:-$SCRIPT_DIR/dataset_b.tsv}"
ONE_GIB=1073741824
ENV_FILE="${PERF_REGISTRY_ENV_FILE:-$REPO_ROOT/deploy/k8s/Selectel/.env}"

if [[ ! -f "$INPUT_TSV" ]]; then
  echo "build-datasets: input TSV not found: $INPUT_TSV" >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "build-datasets: need $ENV_FILE (REGISTRY=...) or PERF_REGISTRY_ENV_FILE=..." >&2
  exit 1
fi

set -a
# shellcheck source=/dev/null
source "$ENV_FILE"
set +a

: "${REGISTRY:?build-datasets: set REGISTRY in $ENV_FILE (как для init-performance-registry-images)}"

REGISTRY="${REGISTRY%/}"
if [[ "$REGISTRY" == */* ]]; then
  REG_PREFIX="$REGISTRY"
  REG_HOST="${REGISTRY%%/*}"
else
  REG_HOST="${REGISTRY_LOGIN_HOST:-cr.selcloud.ru}"
  REG_PREFIX="${REG_HOST}/${REGISTRY}"
fi

_host_slash="${REG_HOST}/"
REG_REPO_PREFIX="${REG_PREFIX#"$_host_slash"}"

if [[ -z "${REGISTRY_PUSH_REPO_STRIP_LIBRARY:-}" ]]; then
  if [[ "$REG_HOST" == *selcloud.ru ]]; then
    REGISTRY_PUSH_REPO_STRIP_LIBRARY=1
  else
    REGISTRY_PUSH_REPO_STRIP_LIBRARY=0
  fi
fi

STRIP_NUM=0
[[ "$REGISTRY_PUSH_REPO_STRIP_LIBRARY" == 1 ]] && STRIP_NUM=1

mkdir -p "$(dirname "$OUT_A")" "$(dirname "$OUT_B")"

awk -F '\t' -v OFS='\t' -v out_a="$OUT_A" -v out_b="$OUT_B" -v one_gib="$ONE_GIB" \
  -v reg_rp="$REG_REPO_PREFIX" -v strip="$STRIP_NUM" '
  function hub_to_mirror(repo,   push) {
    push = repo
    if (strip == 1 && index(repo, "library/") == 1) {
      push = substr(repo, length("library/") + 1)
    }
    return reg_rp "/" push
  }
  NR == 1 {
    print $0 > out_a
    print $0 > out_b
    next
  }
  NF < 4 { next }
  {
    $1 = hub_to_mirror($1)
    size_bytes = $3 + 0
    if (size_bytes >= one_gib) {
      print $0 >> out_b
    } else {
      print $0 >> out_a
    }
  }
' "$INPUT_TSV"

echo "build-datasets: REG_HOST=$REG_HOST REG_REPO_PREFIX=$REG_REPO_PREFIX strip_library=$REGISTRY_PUSH_REPO_STRIP_LIBRARY" >&2
echo "Wrote dataset A: $OUT_A"
echo "Wrote dataset B: $OUT_B"
