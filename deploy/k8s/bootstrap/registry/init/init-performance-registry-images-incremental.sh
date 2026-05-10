#!/usr/bin/env bash
# Инкрементальное зеркало: только строки images_list.sizes.tsv *ниже* сохранённой отметки (новые строки в конце файла).
#
# После полного зеркала через init-performance-registry-images.sh зафиксируйте отметку:
#   ./init-performance-registry-images-incremental.sh --init-state [path/to/.env]
# Затем дописывайте строки в TSV и запускайте:
#   ./init-performance-registry-images-incremental.sh [path/to/.env]
#
# Состояние: .performance-registry-images-last-line (номер последней учтённой строки всего файла TSV).
# Переопределить список: RIID_IMAGES_LIST=/path/to.tsv
#
# Опции:
#   --init-state       записать текущий wc -l TSV в state (без pull/push)
#   --from-line N      один раз взять хвост с строки N (включительно); state всё равно обновится до EOF после успеха
#   --no-update-state  не обновлять state после прогона (отладка)
#
# Внутри: REGISTRY_MIRROR_TSV_ONLY + допись smoke-map и журнала ошибок см. init-performance-registry-images.sh
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DIR/../../../../../.." && pwd)"
FULL="${RIID_IMAGES_LIST:-$DIR/../images_list.sizes.tsv}"
STATE="$DIR/.performance-registry-images-last-line"
INIT_STATE_ONLY=0
FROM_LINE=""
NO_UPDATE_STATE=0
ENV_FILE=""
POSITIONAL=()

usage() {
  sed -n '1,22p' "$0" | tail -n +2 | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --init-state)
      INIT_STATE_ONLY=1
      shift
      ;;
    --from-line)
      [[ -n "${2:-}" ]] || { echo "init-performance-registry-images-incremental: --from-line needs N" >&2; exit 1; }
      FROM_LINE="$2"
      shift 2
      ;;
    --no-update-state)
      NO_UPDATE_STATE=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      POSITIONAL+=("$1")
      shift
      ;;
  esac
done

if [[ ${#POSITIONAL[@]} -gt 0 ]]; then
  ENV_FILE="${POSITIONAL[0]}"
fi
ENV_FILE="${ENV_FILE:-$REPO_ROOT/deploy/k8s/config/.env}"

if [[ ! -f "$FULL" ]]; then
  echo "init-performance-registry-images-incremental: нет $FULL" >&2
  exit 1
fi

total_lines=$(wc -l < "$FULL" | tr -d ' ')

if [[ "$INIT_STATE_ONLY" == 1 ]]; then
  echo "$total_lines" > "$STATE"
  echo "init-performance-registry-images-incremental: state = $total_lines строк в $(basename "$FULL") (следующие запуски возьмут хвост после этой строки)." >&2
  exit 0
fi

if [[ -n "$FROM_LINE" ]]; then
  if [[ ! "$FROM_LINE" =~ ^[1-9][0-9]*$ ]]; then
    echo "init-performance-registry-images-incremental: --from-line должен быть положительным целым" >&2
    exit 1
  fi
  start="$FROM_LINE"
else
  if [[ ! -f "$STATE" ]]; then
    echo "init-performance-registry-images-incremental: нет $STATE" >&2
    echo "После полного init-performance-registry-images.sh выполните один раз:" >&2
    echo "  $0 --init-state [$ENV_FILE]" >&2
    echo "Или укажите явно: $0 --from-line N [$ENV_FILE]" >&2
    exit 1
  fi
  last="$(tr -d ' \n' < "$STATE")"
  if [[ ! "$last" =~ ^[0-9]+$ ]]; then
    echo "init-performance-registry-images-incremental: испорчен state в $STATE" >&2
    exit 1
  fi
  start=$((last + 1))
fi

if [[ "$start" -gt "$total_lines" ]]; then
  echo "init-performance-registry-images-incremental: новых строк нет (первая строка хвоста $start > строк в файле $total_lines)." >&2
  exit 0
fi

TMP="$(mktemp)"
cleanup() { rm -f "$TMP"; }
trap cleanup EXIT

if [[ "$start" -eq 1 ]]; then
  cp "$FULL" "$TMP"
else
  {
    head -n1 "$FULL"
    tail -n "+$start" "$FULL"
  } > "$TMP"
fi

new_count=$((total_lines - start + 1))
echo "init-performance-registry-images-incremental: строки $start–$total_lines ($new_count строк с хвостом) → временный TSV" >&2

export RIID_IMAGES_LIST="$TMP"
export REGISTRY_MIRROR_TSV_ONLY=1
export REGISTRY_MIRROR_APPEND_SMOKE_MAP=1
export REGISTRY_MIRROR_APPEND_FAIL_LOG=1

bash "$DIR/init-performance-registry-images.sh" "$ENV_FILE"

if [[ "$NO_UPDATE_STATE" != 1 ]]; then
  echo "$total_lines" > "$STATE"
  echo "init-performance-registry-images-incremental: state обновлён до строки $total_lines." >&2
fi
