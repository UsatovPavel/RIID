#!/usr/bin/env bash
# Проверяет egress tc (htb/netem) на всех нодах кластера через privileged Pod + hostNetwork.
# Не меняет qdisc — только читает `tc qdisc show` / `tc class show`.
#
# По умолчанию — каждая нода из `kubectl get nodes` (control-plane и worker).
# Одна нода: аргумент node-name или env NODE=...
#
# Env: NODE — одна нода (опционально); TC_CHECK_NAMESPACE (default riid-system),
#      TC_CHECK_IMAGE (default ubuntu:24.04), TC_CHECK_TIMEOUT_SEC (default 300),
#      CLUSTER_CONFIG — подсказка ожидаемого bandwidth на registry-ноде.
#
# Usage: registry-node-tc-check.sh [node-name]
set -euo pipefail

TARGET_NODE="${1:-${NODE:-}}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PERF_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
K8S_ROOT="$(cd "${PERF_DIR}/.." && pwd)"
CLUSTER_CONFIG="${CLUSTER_CONFIG:-${K8S_ROOT}/config/config.yaml}"

TC_NS="${TC_CHECK_NAMESPACE:-riid-system}"
TC_IMAGE="${TC_CHECK_IMAGE:-ubuntu:24.04}"
TC_TIMEOUT_SEC="${TC_CHECK_TIMEOUT_SEC:-300}"

command -v kubectl >/dev/null 2>&1 || {
  echo "$0: kubectl not found" >&2
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

kubectl get ns "$TC_NS" >/dev/null 2>&1 || kubectl create namespace "$TC_NS" >/dev/null

_expected_mbit() {
  command -v yq >/dev/null 2>&1 || return 0
  [[ -f "$CLUSTER_CONFIG" ]] || return 0
  local prefix bw_raw n
  if yq e 'has("network_parameters")' "$CLUSTER_CONFIG" 2>/dev/null | grep -q true; then
    prefix='.network_parameters'
  elif yq e 'has("newtork_parameters")' "$CLUSTER_CONFIG" 2>/dev/null | grep -q true; then
    prefix='.newtork_parameters'
  else
    return 0
  fi
  bw_raw="$(yq e "${prefix}.bandwidth // \"\"" "$CLUSTER_CONFIG" 2>/dev/null || printf '')"
  n="$(echo "$bw_raw" | LC_ALL=C awk '{
    sub(/^[[:space:]]+/, "");
    if (match($0, /^[0-9]+/))
      print substr($0, RSTART, RLENGTH);
  }')"
  if [[ -n "$n" ]] && [[ "$n" =~ ^[0-9]+$ ]] && ((n > 0)); then
    echo $((n * 8))
  fi
}

REGISTRY_LABELED_NODE="$(kubectl get nodes -l riid.registry=true -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
EXPECTED_MBIT="$(_expected_mbit || true)"
if [[ -n "$EXPECTED_MBIT" ]]; then
  echo "expected_registry_tc: bandwidth=${EXPECTED_MBIT}mbit (from ${CLUSTER_CONFIG})" >&2
fi
if [[ -n "$REGISTRY_LABELED_NODE" ]]; then
  echo "registry_labeled_node: ${REGISTRY_LABELED_NODE}" >&2
fi
echo >&2

_list_all_nodes() {
  kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
}

if [[ -n "$TARGET_NODE" ]]; then
  NODES=("$TARGET_NODE")
else
  mapfile -t NODES < <(_list_all_nodes)
fi

if ((${#NODES[@]} == 0)); then
  echo "$0: nodes not found" >&2
  exit 1
fi

echo "checking_nodes: count=${#NODES[@]} nodes=${NODES[*]}" >&2
echo >&2

INNER_BODY=$(
  cat <<'EOS'
export DEBIAN_FRONTEND=noninteractive
set -eo pipefail
apt-get update -qq && apt-get install -y --no-install-recommends iproute2 ca-certificates -qq >/dev/null
_IFACE=""
_IFACE="$(ip route show default 2>/dev/null | awk 'NR==1 {print $5; exit}' )"
[[ -n "$_IFACE" ]] || _IFACE="$(ip -o link show 2>/dev/null | awk -F': ' '$2!="lo"{print $2; exit}' )"
[[ -n "$_IFACE" ]] || { echo "registry-node-tc-check: cannot detect iface"; exit 1; }
echo "iface=${_IFACE}"
tc qdisc show dev "$_IFACE"
tc class show dev "$_IFACE" 2>/dev/null || true
if tc qdisc show dev "$_IFACE" | grep -qE 'qdisc htb 1:|qdisc netem'; then
  echo "tc_limited=yes"
else
  echo "tc_limited=no"
fi
echo registry-node-tc-check OK
EOS
)

_check_node() {
  local node="$1"
  local pod="riid-registry-node-tc-check-${node}"
  local yaml_path phase deadline

  yaml_path="$(mktemp)"

  kubectl -n "$TC_NS" delete pod "$pod" --ignore-not-found --wait=true >/dev/null 2>&1 || true

  {
    printf '%s\n' "apiVersion: v1"
    printf '%s\n' "kind: Pod"
    printf '%s\n' "metadata:"
    printf '%s\n' "  name: ${pod}"
    printf '%s\n' "  namespace: ${TC_NS}"
    printf '%s\n' "  labels:"
    printf '%s\n' '    app.kubernetes.io/name: riid-registry-node-tc-check'
    printf '%s\n' "spec:"
    printf '%s\n' '  hostNetwork: true'
    printf '%s\n' "  nodeName: ${node}"
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
    printf '%s\n' '      command:'
    printf '%s\n' '        - /bin/bash'
    printf '%s\n' '        - -lc'
    printf '%s\n' '      args:'
    printf '%s\n' '        - |-'
    while IFS= read -r line || [[ -n "$line" ]]; do
      printf '            %s\n' "$line"
    done <<<"$INNER_BODY"
  } >"$yaml_path"

  kubectl apply -f "$yaml_path" >/dev/null

  deadline=$((SECONDS + TC_TIMEOUT_SEC))
  phase=""
  while [[ $SECONDS -lt $deadline ]]; do
    phase="$(kubectl -n "$TC_NS" get pod "$pod" -o jsonpath='{.status.phase}' 2>/dev/null || printf '')"
    if [[ "$phase" == "Succeeded" ]]; then
      break
    fi
    if [[ "$phase" == "Failed" ]]; then
      kubectl -n "$TC_NS" get pod "$pod" -o wide >&2 || true
      kubectl -n "$TC_NS" logs "$pod" -c tc >&2 || true
      kubectl -n "$TC_NS" delete pod "$pod" --wait=false >/dev/null 2>&1 || true
      rm -f "$yaml_path"
      return 1
    fi
    sleep 2
  done

  if [[ "$phase" != "Succeeded" ]]; then
    kubectl -n "$TC_NS" get pod "$pod" -o wide >&2 || true
    kubectl -n "$TC_NS" logs "$pod" -c tc >&2 || true
    kubectl -n "$TC_NS" delete pod "$pod" --wait=false >/dev/null 2>&1 || true
    rm -f "$yaml_path"
    return 1
  fi

  kubectl -n "$TC_NS" logs "$pod" -c tc
  kubectl -n "$TC_NS" delete pod "$pod" --wait=false >/dev/null
  rm -f "$yaml_path"
}

LIMITED_NODES=()
STALE_LIMITED_NODES=()
FAILED=0

for node in "${NODES[@]}"; do
  node_registry="$(kubectl get node "$node" -o jsonpath='{.metadata.labels.riid\.registry}' 2>/dev/null || true)"
  node_monitoring="$(kubectl get node "$node" -o jsonpath='{.metadata.labels.riid\.monitoring}' 2>/dev/null || true)"
  node_role="$(kubectl get node "$node" -o jsonpath='{.metadata.labels.node-role\.kubernetes\.io/control-plane}' 2>/dev/null || true)"
  [[ -z "$node_role" ]] && node_role="$(kubectl get node "$node" -o jsonpath='{.metadata.labels.node-role\.kubernetes\.io/master}' 2>/dev/null || true)"
  echo "========== ${node} (registry=${node_registry:-false} monitoring=${node_monitoring:-false} control-plane=${node_role:-false}) =========="
  output=""
  if ! output="$(_check_node "$node")"; then
    echo "WARN: check failed on node=${node}" >&2
    FAILED=1
    echo
    continue
  fi
  printf '%s\n' "$output"
  if grep -q '^tc_limited=yes$' <<<"$output"; then
    LIMITED_NODES+=("$node")
    if [[ -n "$REGISTRY_LABELED_NODE" && "$node" != "$REGISTRY_LABELED_NODE" ]]; then
      STALE_LIMITED_NODES+=("$node")
    fi
  fi
  echo
done

echo "========== summary =========="
if ((${#LIMITED_NODES[@]} == 0)); then
  echo "tc_limited_nodes: (none)"
else
  echo "tc_limited_nodes: ${LIMITED_NODES[*]}"
fi

if ((${#STALE_LIMITED_NODES[@]} > 0)); then
  echo "WARN: tc on non-registry nodes (stale?): ${STALE_LIMITED_NODES[*]}" >&2
  echo "clear per node: REGISTRY_NODE=<node> make -C deploy/k8s/performance registry-node-tc-clear" >&2
  FAILED=1
fi

if [[ -n "$REGISTRY_LABELED_NODE" && -n "$EXPECTED_MBIT" ]]; then
  registry_limited=0
  for n in "${LIMITED_NODES[@]}"; do
    [[ "$n" == "$REGISTRY_LABELED_NODE" ]] && registry_limited=1
  done
  if ((registry_limited == 0)); then
    echo "WARN: registry node ${REGISTRY_LABELED_NODE} has no tc, expected ~${EXPECTED_MBIT}mbit" >&2
    echo "apply: make -C deploy/k8s/performance registry-node-tc-apply" >&2
    FAILED=1
  fi
fi

if ((FAILED != 0)); then
  exit 1
fi
