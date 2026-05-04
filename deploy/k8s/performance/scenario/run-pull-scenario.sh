#!/usr/bin/env bash
set -euo pipefail

NS="${RIID_NAMESPACE:-riid-system}"
CONTAINER="${RIID_CONTAINER:-riid}"
LABEL_SELECTOR="${RIID_LABEL_SELECTOR:-app.kubernetes.io/name=riid}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="${BACKEND_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)/backend}"

MODE="${MODE:-rolling}"              # rolling | recreate
BACKEND="${BACKEND:-riid}"           # riid | podman
CONCURRENCY="${CONCURRENCY:-2}"      # used in rolling mode
EXPECTED_RIID_PODS="${EXPECTED_RIID_PODS:-10}"  # 0 disables strict count check

SCENARIO="${SCENARIO:-scenario-unnamed}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-}"
IMAGE_REFERENCE="${IMAGE_REFERENCE:-latest}"
RUNTIME_ID="${RUNTIME_ID:-podman}"
OUTPUT_TSV="${OUTPUT_TSV:-${OUTPUT_CSV:-}}"
BACKEND_CMD="$BACKEND_DIR/${BACKEND}.sh"
DATASET_FILE="${DATASET_FILE:-}"

if [[ "$MODE" != "rolling" && "$MODE" != "recreate" ]]; then
  echo "Unsupported MODE=$MODE. Use rolling|recreate." >&2
  exit 2
fi

if [[ "$BACKEND" != "riid" && "$BACKEND" != "podman" ]]; then
  echo "Unsupported BACKEND=$BACKEND. Use riid|podman." >&2
  exit 2
fi

if ! [[ "$CONCURRENCY" =~ ^[0-9]+$ ]] || [[ "$CONCURRENCY" -le 0 ]]; then
  echo "CONCURRENCY must be a positive integer, got: $CONCURRENCY" >&2
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

now_ms() {
  date +%s%3N
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
    bash "$BACKEND_CMD" "$pod"; then
    code=0
  else
    code=$?
  fi
  end="$(now_ms)"
  duration=$((end - start))

  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$SCENARIO" "$MODE" "$IMAGE_REPOSITORY:$IMAGE_REFERENCE" "$pod" "$BACKEND" \
    "$start" "$end" "$duration" "$code"

  return "$code"
}

emit_header() {
  printf 'scenario,mode,image,pod,backend,start_ms,end_ms,duration_ms,exit_code\n'
}

run_rolling() {
  local -a pods=("$@")
  local failed=0
  local i=0
  while ((i < ${#pods[@]})); do
    local -a pids=()
    local -a batch_pods=()
    local limit=$((i + CONCURRENCY))
    while ((i < ${#pods[@]} && i < limit)); do
      local pod="${pods[$i]}"
      batch_pods+=("$pod")
      (
        run_one "$pod"
      ) &
      pids+=("$!")
      ((i++))
    done

    local idx=0
    for pid in "${pids[@]}"; do
      if ! wait "$pid"; then
        echo "FAILED pod=${batch_pods[$idx]} mode=rolling backend=$BACKEND" >&2
        failed=1
      fi
      ((idx++))
    done
  done
  return "$failed"
}

run_recreate() {
  local -a pods=("$@")
  local failed=0
  local -a pids=()
  for pod in "${pods[@]}"; do
    (
      run_one "$pod"
    ) &
    pids+=("$!")
  done
  local idx=0
  for pid in "${pids[@]}"; do
    if ! wait "$pid"; then
      echo "FAILED pod=${pods[$idx]} mode=recreate backend=$BACKEND" >&2
      failed=1
    fi
    ((idx++))
  done
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
  echo "Running scenario=$SCENARIO mode=$MODE backend=$BACKEND pods=${#pods[@]} image=${IMAGE_REPOSITORY}:${IMAGE_REFERENCE}" >&2
  if [[ "$MODE" == "rolling" ]]; then
    run_rolling "${pods[@]}" || run_failed=1
  else
    run_recreate "${pods[@]}" || run_failed=1
  fi
  return "$run_failed"
}

if [[ -n "$OUTPUT_TSV" ]]; then
  mkdir -p "$(dirname "$OUTPUT_TSV")"
  exec > >(tee "$OUTPUT_TSV")
fi

emit_header

failed=0
if [[ -n "$DATASET_FILE" ]]; then
  while IFS=$'\t' read -r repo ref _size_bytes _size_human; do
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

exit "$failed"
