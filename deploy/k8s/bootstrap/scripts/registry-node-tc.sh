#!/usr/bin/env bash
# На ноде с label riid.registry=true настраивает egress tc (latency + полоса) по deploy/k8s/config/config.yaml.
# Секция: .network_parameters (или legacy .newtork_parameters) — bandwidth, latency_additional|latency (мс односторонней задержки в netem).
# bandwidth: выключатель 0|none|off|unlimited или целое N строго в виде «N MB/s» (без дроби). Другие строки — ошибка.
#
# privileged Pod + hostNetwork на нужной ноде; ставит iproute2 (apt из образа ubuntu).
#
# Env: опционально REGISTRY_NODE, CLUSTER_CONFIG, REGISTRY_TC_IMAGE (default ubuntu:24.04),
#      REGISTRY_TC_NAMESPACE (default riid-system), REGISTRY_TC_POD_NAME (default riid-registry-node-tc).
# При отсутствии KUBECONFIG используется дефолт kubectl (~/.kube/config).
#
# Usage: registry-node-tc.sh apply|clear
set -euo pipefail

MODE="${1:-}"
if [[ "$MODE" != "apply" && "$MODE" != "clear" ]]; then
  echo "Usage: $0 apply|clear" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOOTSTRAP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
K8S_ROOT="$(cd "${BOOTSTRAP_DIR}/.." && pwd)"
CLUSTER_CONFIG="${CLUSTER_CONFIG:-${K8S_ROOT}/config/config.yaml}"

TC_POD="${REGISTRY_TC_POD_NAME:-riid-registry-node-tc}"
TC_NS="${REGISTRY_TC_NAMESPACE:-riid-system}"
TC_IMAGE="${REGISTRY_TC_IMAGE:-ubuntu:24.04}"

command -v kubectl >/dev/null 2>&1 || {
  echo "$0: kubectl not found" >&2
  exit 1
}
command -v yq >/dev/null 2>&1 || {
  echo "$0: yq not found" >&2
  exit 1
}

if [[ -n "${KUBECONFIG:-}" ]] && [[ ! -f "${KUBECONFIG}" ]]; then
  echo "$0: файл KUBECONFIG не найден: $KUBECONFIG" >&2
  exit 1
fi

if ! kubectl cluster-info >/dev/null 2>&1; then
  echo "$0: kubectl cluster-info недоступен (контекст/kubeconfig?" >&2
  exit 1
fi

_pick_prefix() {
  if yq e 'has("network_parameters")' "$CLUSTER_CONFIG" 2>/dev/null | grep -q true; then
    printf '.network_parameters'
    return 0
  fi
  if yq e 'has("newtork_parameters")' "$CLUSTER_CONFIG" 2>/dev/null | grep -q true; then
    printf '.newtork_parameters'
    return 0
  fi
  printf ''
}

# Ведущее целое в строке «10 MB/s» → 10.
_bandwidth_mb_s_integer() {
  echo "$1" | LC_ALL=C awk '{
    sub(/^[[:space:]]+/, "");
    if (match($0, /^[0-9]+/))
      print substr($0, RSTART, RLENGTH);
  }'
}

_first_digits() {
  tr -dc '0-9' <<<"$1" | head -c 12 || true
}

_bandwidth_to_mbit() {
  local raw_trim lower n mbit
  raw_trim="$(echo "$1" | tr -s '[:space:]')"
  raw_trim="$(echo "$raw_trim" | sed -e 's/^ *//;s/ *$//')"
  [[ -z "$raw_trim" || "$raw_trim" == '~' ]] && {
    printf '%s\n' ""
    return
  }
  lower="$(echo "$raw_trim" | tr '[:upper:]' '[:lower:]')"
  if grep -qE '^(0|none|off|disable|disabled|unlimited)$' <<<"$lower"; then
    printf '%s\n' ""
    return
  fi
  if grep -qE '[0-9]+\.[0-9]+' <<<"$raw_trim"; then
    echo "$0: bandwidth: только целое N в «N MB/s», без дроби; получено: $raw_trim" >&2
    exit 1
  fi
  if ! grep -qE 'mb/s|mbyte/s' <<<"$lower"; then
    echo "$0: bandwidth: нужен формат «N MB/s» (целое N); получено: $raw_trim" >&2
    exit 1
  fi
  n="$(_bandwidth_mb_s_integer "$raw_trim")"
  if [[ -z "$n" ]] || [[ ! "$n" =~ ^[0-9]+$ ]] || ((n <= 0)); then
    echo "$0: bandwidth: положительное целое N в «N MB/s»; получено: $raw_trim" >&2
    exit 1
  fi
  mbit=$((n * 8))
  printf '%s\n' "${mbit}"
}

_latency_ms() {
  local raw_trim lower ms
  raw_trim="$(echo "$1" | tr -s '[:space:]')"
  raw_trim="$(echo "$raw_trim" | sed -e 's/^ *//;s/ *$//')"
  [[ -z "$raw_trim" || "$raw_trim" == '~' ]] && {
    printf '%s\n' "0"
    return
  }
  lower="$(echo "$raw_trim" | tr '[:upper:]' '[:lower:]')"
  if grep -qE '^(0|none|off)$' <<<"$lower"; then
    printf '%s\n' "0"
    return
  fi
  ms="$(_first_digits "$raw_trim")"
  ms="${ms:-0}"
  if grep -qE '^[[:digit:]]+[[:space:]]*s(\s|$)' <<<"$lower" && ! grep -q 'ms' <<<"$lower"; then
    ms=$((ms * 1000))
  fi
  printf '%s\n' "$ms"
}

if [[ -z "${REGISTRY_NODE:-}" ]]; then
  REGISTRY_NODE="$(kubectl get nodes -l riid.registry=true -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
fi
[[ -n "${REGISTRY_NODE}" ]] || {
  echo "$0: label riid.registry=true не найден; задай REGISTRY_NODE=..." >&2
  exit 1
}

kubectl get ns "$TC_NS" >/dev/null 2>&1 || kubectl create namespace "$TC_NS" >/dev/null

kubectl -n "$TC_NS" delete pod "$TC_POD" --ignore-not-found --wait >/dev/null 2>&1 || true

RIID_TC_MODE="$MODE"
DELAY_MS=0
MBIT=""
if [[ "$MODE" == "apply" ]]; then
  [[ -f "$CLUSTER_CONFIG" ]] || {
    echo "$0: нет конфига: $CLUSTER_CONFIG" >&2
    exit 1
  }
  PREFIX="$(_pick_prefix)"
  [[ -n "$PREFIX" ]] || {
    echo "$0: нет ключей network_parameters / newtork_parameters в $CLUSTER_CONFIG" >&2
    exit 1
  }
  BW_RAW="$(yq e "${PREFIX}.bandwidth // \"\"" "$CLUSTER_CONFIG" 2>/dev/null || printf '%s' '')"
  LAT_RAW="$(yq e "${PREFIX}.latency_additional // ${PREFIX}.latency // \"\"" "$CLUSTER_CONFIG" 2>/dev/null || printf '%s' '')"
  MBIT="$(_bandwidth_to_mbit "$BW_RAW")"
  DELAY_MS="$(_latency_ms "$LAT_RAW")"
  echo "registry-node-tc apply: node=$REGISTRY_NODE ns=$TC_NS mbit=${MBIT:-∞} delay_ms(one-way)=${DELAY_MS}" >&2
fi

INNER_BODY=$(
  cat <<'EOS'
export DEBIAN_FRONTEND=noninteractive
set -eo pipefail
apt-get update -qq && apt-get install -y --no-install-recommends iproute2 ca-certificates -qq >/dev/null
_IFACE=""
_IFACE="$(ip route show default 2>/dev/null | awk 'NR==1 {print $5; exit}' )"
[[ -n "$_IFACE" ]] || _IFACE="$(ip -o link show 2>/dev/null | awk -F': ' '$2!="lo"{print $2; exit}' )"
[[ -n "$_IFACE" ]] || { echo "registry-node-tc: cannot detect iface"; exit 1; }
echo "iface=$_IFACE mode=${RIID_TC_MODE}"

tc qdisc del dev "$_IFACE" root 2>/dev/null || true

if [[ "${RIID_TC_MODE}" == "clear" ]]; then
  tc qdisc show dev "$_IFACE"
  echo registry-node-tc-clear OK
  exit 0
fi

D="${RIID_TC_DELAY_MS}"
M="${RIID_TC_MBIT}"

if [[ -n "$M" && "$M" -gt 0 ]]; then
  if [[ "$D" -gt 0 ]]; then
    tc qdisc add dev "$_IFACE" root handle 1: htb default 10
    tc class add dev "$_IFACE" parent 1: classid 1:10 htb rate "${M}mbit" ceil "${M}mbit"
    tc qdisc add dev "$_IFACE" parent 1:10 handle 20: netem delay "${D}ms"
  else
    tc qdisc add dev "$_IFACE" root handle 1: htb default 10
    tc class add dev "$_IFACE" parent 1: classid 1:10 htb rate "${M}mbit" ceil "${M}mbit"
  fi
else
  if [[ "$D" -gt 0 ]]; then
    tc qdisc add dev "$_IFACE" root netem delay "${D}ms"
  else
    echo "network_parameters: bandwidth и latency/disabled — qdisc удалён только" >&2
  fi
fi
tc qdisc show dev "$_IFACE"
echo registry-node-tc-apply OK
EOS
)

YAML_PATH="$(mktemp)"
cleanup() {
  rm -f "$YAML_PATH"
}
trap cleanup EXIT

apply_env_yaml() {
  printf '%s\n' '      env:'
  printf '%s\n' '        - name: RIID_TC_MODE'
  printf '%s\n' "          value: ${RIID_TC_MODE}"
  printf '%s\n' '        - name: RIID_TC_DELAY_MS'
  printf '%s\n' "          value: \"${DELAY_MS}\""
  printf '%s\n' '        - name: RIID_TC_MBIT'
  printf '%s\n' "          value: \"${MBIT}\""
}

{
  printf '%s\n' "apiVersion: v1"
  printf '%s\n' "kind: Pod"
  printf '%s\n' "metadata:"
  printf '%s\n' "  name: ${TC_POD}"
  printf '%s\n' "  namespace: ${TC_NS}"
  printf '%s\n' "  labels:"
  printf '%s\n' '    app.kubernetes.io/name: riid-registry-node-tc'
  printf '%s\n' "spec:"
  printf '%s\n' '  hostNetwork: true'
  printf '%s\n' "  nodeName: ${REGISTRY_NODE}"
  printf '%s\n' '  restartPolicy: Never'
  printf '%s\n' '  tolerations:'
  printf '%s\n' '    - operator: Exists'
  printf '%s\n' '      effect: NoSchedule'
  printf '%s\n' '    - operator: Exists'
  printf '%s\n' '      effect: NoExecute'
  printf '%s\n' '  containers:'
  printf '%s\n' '    - name: tc'
  printf '%s\n' "      image: ${TC_IMAGE}"
  printf '%s\n' '      imagePullPolicy: IfNotPresent'
  printf '%s\n' '      securityContext:'
  printf '%s\n' '        privileged: true'
  apply_env_yaml
  printf '%s\n' '      command:'
  printf '%s\n' '        - /bin/bash'
  printf '%s\n' '        - -lc'
  printf '%s\n' '      args:'
  printf '%s\n' '        - |-'
  while IFS= read -r line || [[ -n "$line" ]]; do
    printf '            %s\n' "$line"
  done <<<"$INNER_BODY"
} >"$YAML_PATH"

kubectl apply -f "$YAML_PATH" >/dev/null

echo "$0: ожидание pod/$TC_POD ..."
_deadline=$((SECONDS + 300))
_phase=""
while [[ $SECONDS -lt $_deadline ]]; do
  _phase="$(kubectl -n "$TC_NS" get pod "$TC_POD" -o jsonpath='{.status.phase}' 2>/dev/null || printf '')"
  if [[ "$_phase" == "Succeeded" ]]; then
    break
  fi
  if [[ "$_phase" == "Failed" ]]; then
    kubectl -n "$TC_NS" get pod "$TC_POD" -o wide >&2 || true
    kubectl -n "$TC_NS" logs "$TC_POD" -c tc >&2 || true
    exit 1
  fi
  sleep 2
done
[[ "$_phase" == "Succeeded" ]] || {
  kubectl -n "$TC_NS" get pod "$TC_POD" -o wide >&2 || true
  kubectl -n "$TC_NS" logs "$TC_POD" -c tc >&2 || true
  exit 1
}
kubectl -n "$TC_NS" logs "$TC_POD" -c tc
kubectl -n "$TC_NS" delete pod "$TC_POD" --wait=false >/dev/null
