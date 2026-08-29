#!/usr/bin/env bash
set -euo pipefail

NS="${RIID_NAMESPACE:-riid-system}"
CONTAINER="${RIID_CONTAINER:-riid}"
LABEL_SELECTOR="${RIID_LABEL_SELECTOR:-app.kubernetes.io/name=riid}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="${BACKEND_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)/backend}"

# Сценарий один — recreate: все поды тянут образ одновременно. Rolling убран
# целиком, вместе с CONCURRENCY: матрица AGENT-99 сравнивает армы только на
# одновременной нагрузке. Старый MODE=rolling ловится явной ошибкой, чтобы
# сохранённые команды не отработали молча под другой семантикой.
if [[ -n "${MODE:-}" && "${MODE}" != "recreate" ]]; then
  echo "MODE=${MODE} не поддерживается: остался только recreate (rolling убран)." >&2
  exit 2
fi
MODE="recreate"
BACKEND="${BACKEND:-riid}"           # riid | bare | dfinit

K8S_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CLUSTER_CONFIG="${CLUSTER_CONFIG:-$K8S_ROOT/config/config.yaml}"
if [[ ! -v EXPECTED_RIID_PODS ]] || [[ -z "${EXPECTED_RIID_PODS}" ]]; then
  if [[ -f "$CLUSTER_CONFIG" ]] && command -v yq >/dev/null 2>&1; then
    EXPECTED_RIID_PODS="$(yq e '.cluster_topology.workers // 0' "$CLUSTER_CONFIG" 2>/dev/null || echo 0)"
  else
    EXPECTED_RIID_PODS=0
  fi
fi
EXPECTED_RIID_PODS="${EXPECTED_RIID_PODS//[[:space:]]/}"

SCENARIO="${SCENARIO:-scenario-unnamed}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-}"
IMAGE_REFERENCE="${IMAGE_REFERENCE:-latest}"
RUNTIME_ID="${RUNTIME_ID:-podman}"
# Движок для BACKEND=bare|dfinit; для BACKEND=riid движок задаёт RUNTIME_ID.
ENGINE="${ENGINE:-}"
# Метка арма в TSV: источник+движок. Без движка шесть армов матрицы неразличимы
# в сводках — "bare" одинаково и для podman, и для containerd.
BACKEND_LABEL="$BACKEND${ENGINE:+-$ENGINE}"
OUTPUT_TSV="${OUTPUT_TSV:-${OUTPUT_CSV:-}}"
BACKEND_CMD="$BACKEND_DIR/${BACKEND}.sh"
DATASET_FILE="${DATASET_FILE:-}"
REGISTRY_TX_NAMESPACE="${REGISTRY_TX_NAMESPACE:-riid-system}"
REGISTRY_TX_POD_NAME="${REGISTRY_TX_POD_NAME:-riid-registry-node-tx-bytes}"
REGISTRY_TX_IMAGE="${REGISTRY_TX_IMAGE:-ubuntu:24.04}"
REGISTRY_TX_HELPER="${REGISTRY_TX_HELPER:-$SCRIPT_DIR/registry-tx.sh}"

# Список бэкендов не захардкожен: он равен набору backend/*.sh, а конкретное
# отсутствие ловится проверкой BACKEND_CMD ниже. Движок для bare/dfinit
# задаётся отдельной переменной ENGINE (podman|containerd), см. backend/engine/.

if ! [[ "$EXPECTED_RIID_PODS" =~ ^[0-9]+$ ]]; then
  echo "EXPECTED_RIID_PODS must be a non-negative integer (or empty for config), got: $EXPECTED_RIID_PODS" >&2
  exit 2
fi

if [[ -n "${KUBECONFIG:-}" && ! -f "$KUBECONFIG" ]]; then
  echo "kubeconfig not found: $KUBECONFIG" >&2
  exit 2
fi

if [[ ! -f "$BACKEND_CMD" ]]; then
  echo "Backend script not found: $BACKEND_CMD" >&2
  exit 2
fi

if [[ -n "$DATASET_FILE" && ! -f "$DATASET_FILE" ]]; then
  echo "Dataset file not found: $DATASET_FILE" >&2
  exit 2
fi

if [[ ! -f "$REGISTRY_TX_HELPER" ]]; then
  echo "Registry tx helper script not found: $REGISTRY_TX_HELPER" >&2
  exit 2
fi

# shellcheck source=/dev/null
source "$REGISTRY_TX_HELPER"

# Миллисекунды с эпохи. Не через `date +%s%3N`: ширину у %N понимает только GNU
# coreutils, а uutils-реализация (стоит в Ubuntu 26) её молча игнорирует и отдаёт
# наносекунды — duration_ms тогда завышен в 10^6 раз, а из-за потери ведущего нуля
# в дробной части местами уходит в минус. Поэтому %s.%N и арифметика руками.
now_ms() {
  local t s ns
  t="$(date +%s.%N)"
  s="${t%%.*}"
  ns="${t#*.}"
  if [[ "$ns" == "$t" ]] || ! [[ "$ns" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "$((s * 1000))"
    return
  fi
  ns="${ns}00000000"
  ns="${ns:0:9}"
  printf '%s\n' "$((s * 1000 + 10#$ns / 1000000))"
}

list_running_pods() {
  kubectl -n "$NS" get pods -l "$LABEL_SELECTOR" \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.phase}{"\n"}{end}' \
    | awk '$2=="Running"{print $1}'
}

run_one() {
  local pod="$1"
  local start end duration code
  start="$(now_ms)"
  if NS="$NS" \
    CONTAINER="$CONTAINER" \
    IMAGE_REPOSITORY="$IMAGE_REPOSITORY" \
    IMAGE_REFERENCE="$IMAGE_REFERENCE" \
    RUNTIME_ID="$RUNTIME_ID" \
    ENGINE="$ENGINE" \
    bash "$BACKEND_CMD" "$pod"; then
    code=0
  else
    code=$?
  fi
  end="$(now_ms)"
  duration=$((end - start))

  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$SCENARIO" "$MODE" "$IMAGE_REPOSITORY:$IMAGE_REFERENCE" "$pod" "$BACKEND_LABEL" \
    "$start" "$end" "$duration" "$code"

  return "$code"
}

emit_header() {
  printf 'scenario,mode,image,pod,backend,start_ms,end_ms,duration_ms,exit_code\n'
}

run_recreate() {
  local -a pods=("$@")
  local failed=0
  local -a pids=()
  local start_ms end_ms duration_ms

  start_ms="$(now_ms)"
  for pod in "${pods[@]}"; do
    (
      run_one "$pod"
    ) &
    pids+=("$!")
  done
  local idx=0
  for pid in "${pids[@]}"; do
    if ! wait "$pid"; then
      echo "FAILED pod=${pods[$idx]} mode=recreate backend=$BACKEND_LABEL" >&2
      failed=1
    fi
    ((idx++))
  done
  end_ms="$(now_ms)"
  duration_ms=$((end_ms - start_ms))

  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$SCENARIO" "$MODE" "$IMAGE_REPOSITORY:$IMAGE_REFERENCE" "AGGREGATE" "$BACKEND_LABEL" \
    "$start_ms" "$end_ms" "$duration_ms" "$failed"

  return "$failed"
}

mapfile -t pods < <(list_running_pods)
if ((${#pods[@]} == 0)); then
  echo "No running RIID pods in namespace=$NS selector=$LABEL_SELECTOR" >&2
  exit 1
fi

if [[ "$EXPECTED_RIID_PODS" =~ ^[0-9]+$ ]] && ((EXPECTED_RIID_PODS > 0)) && ((${#pods[@]} != EXPECTED_RIID_PODS)); then
  echo "Expected $EXPECTED_RIID_PODS running RIID pods, got ${#pods[@]}" >&2
  exit 1
fi

run_for_current_image() {
  local run_failed=0
  echo "Running scenario=$SCENARIO mode=$MODE backend=$BACKEND_LABEL pods=${#pods[@]} image=${IMAGE_REPOSITORY}:${IMAGE_REFERENCE}" >&2
  run_recreate "${pods[@]}" || run_failed=1
  return "$run_failed"
}

if [[ -n "$OUTPUT_TSV" ]]; then
  mkdir -p "$(dirname "$OUTPUT_TSV")"
  exec > >(tee "$OUTPUT_TSV")
fi

emit_header

failed=0
registry_tx_before=""
registry_tx_after=""
registry_tx_delta=""

if probe_before="$(registry_node_probe_tx)"; then
  registry_tx_before="$(awk -F '\t' '{print $2}' <<<"$probe_before")"
else
  echo "WARN: failed to measure registry tx_bytes before scenario" >&2
fi

if [[ -n "$DATASET_FILE" ]]; then
  while IFS=$'\t' read -r repo ref _size_bytes _size_human || [[ -n "${repo:-}" ]]; do
    if [[ "$repo" == "repository" || -z "$repo" || "$repo" == \#* ]]; then
      continue
    fi
    IMAGE_REPOSITORY="$repo"
    IMAGE_REFERENCE="${ref:-latest}"
    run_for_current_image || failed=1
  done <"$DATASET_FILE"
else
  if [[ -z "$IMAGE_REPOSITORY" ]]; then
    echo "Set IMAGE_REPOSITORY (and optional IMAGE_REFERENCE) or DATASET_FILE." >&2
    exit 2
  fi
  run_for_current_image || failed=1
fi

if probe_after="$(registry_node_probe_tx)"; then
  registry_tx_after="$(awk -F '\t' '{print $2}' <<<"$probe_after")"
else
  echo "WARN: failed to measure registry tx_bytes after scenario" >&2
fi

if registry_tx_is_uint "$registry_tx_before" && registry_tx_is_uint "$registry_tx_after" && ((registry_tx_after >= registry_tx_before)); then
  registry_tx_delta=$((registry_tx_after - registry_tx_before))
elif registry_tx_is_uint "$registry_tx_before" && registry_tx_is_uint "$registry_tx_after"; then
  echo "WARN: registry tx_bytes counter wrapped or reset (before=$registry_tx_before after=$registry_tx_after)" >&2
fi

printf '# registry_tx_bytes_before:\t%s\n' "${registry_tx_before:--}"
printf '# registry_tx_bytes_after:\t%s\n' "${registry_tx_after:--}"
printf '# registry_tx_bytes_delta:\t%s\n' "${registry_tx_delta:--}"

exit "$failed"
