#!/usr/bin/env bash

# Shared helpers for measuring registry-node tx_bytes in performance scenarios.
# Expected env vars (with defaults handled by caller):
#   REGISTRY_TX_NAMESPACE
#   REGISTRY_TX_POD_NAME
#   REGISTRY_TX_IMAGE

registry_tx_is_uint() {
  [[ "${1:-}" =~ ^[0-9]+$ ]]
}

registry_node_probe_tx() {
  local reg_node yaml_path probe_body out_line tx iface deadline phase
  reg_node="$(kubectl get nodes -l riid.registry=true -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  [[ -n "$reg_node" ]] || {
    echo "registry-tx: node with label riid.registry=true not found" >&2
    return 1
  }

  kubectl get ns "$REGISTRY_TX_NAMESPACE" >/dev/null 2>&1 || {
    echo "registry-tx: namespace $REGISTRY_TX_NAMESPACE not found" >&2
    return 1
  }

  kubectl -n "$REGISTRY_TX_NAMESPACE" delete pod "$REGISTRY_TX_POD_NAME" --ignore-not-found --wait >/dev/null 2>&1 || true

  # Probe script in a quoted heredoc file so outer YAML <<EOF does not expand $(...) on the workstation.
  probe_body="$(mktemp)"
  yaml_path="$(mktemp)"
  cleanup_probe_yaml() {
    rm -f "$probe_body" "$yaml_path"
  }
  trap cleanup_probe_yaml RETURN

  cat >"$probe_body" <<'REGISTRY_TX_PROBE_SCRIPT'
set -eu
iface=""
# 1) IPv4 default route (works without iproute2 `ip`; aligns with registry-node-tc.sh intent).
if [ -r /proc/net/route ]; then
  iface="$(awk '$2 == "00000000" { print $1; exit }' /proc/net/route 2>/dev/null || true)"
fi
# 2) default route line may be "default dev eth0 ..." -> pick iface after "dev", not fixed $5.
if [ -z "$iface" ] && command -v ip >/dev/null 2>&1; then
  iface="$(ip route show default 2>/dev/null | awk '{ for (i = 1; i < NF; i++) if ($i == "dev") { print $(i + 1); exit } }')"
fi
if [ -z "$iface" ] && command -v ip >/dev/null 2>&1; then
  iface="$(ip route show default 2>/dev/null | awk 'NR == 1 { print $5; exit }')"
fi
if [ -z "$iface" ] && command -v ip >/dev/null 2>&1; then
  iface="$(ip -o link show 2>/dev/null | awk -F': ' '$2 != "lo" { print $2; exit }')"
fi
if [ -z "$iface" ]; then
  for n in /sys/class/net/*; do
    [ -e "$n" ] || continue
    b="$(basename "$n")"
    [ "$b" = "lo" ] && continue
    iface="$b"
    break
  done
fi
if [ -z "$iface" ]; then
  echo iface= tx_bytes=
  exit 1
fi
tx="$(cat "/sys/class/net/${iface}/statistics/tx_bytes")"
echo iface=$iface tx_bytes=$tx
REGISTRY_TX_PROBE_SCRIPT

  {
    cat <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: ${REGISTRY_TX_POD_NAME}
  namespace: ${REGISTRY_TX_NAMESPACE}
spec:
  hostNetwork: true
  nodeName: ${reg_node}
  restartPolicy: Never
  tolerations:
    - operator: Exists
      effect: NoSchedule
    - operator: Exists
      effect: NoExecute
  containers:
    - name: probe
      image: ${REGISTRY_TX_IMAGE}
      imagePullPolicy: IfNotPresent
      securityContext:
        privileged: true
      command: ["/bin/sh", "-c"]
      args:
        - |
EOF
    sed 's/^/          /' "$probe_body"
  } >"$yaml_path"

  kubectl apply -f "$yaml_path" >/dev/null

  deadline=$((SECONDS + 120))
  phase=""
  while [[ $SECONDS -lt $deadline ]]; do
    phase="$(kubectl -n "$REGISTRY_TX_NAMESPACE" get pod "$REGISTRY_TX_POD_NAME" -o jsonpath='{.status.phase}' 2>/dev/null || printf '')"
    if [[ "$phase" == "Succeeded" ]]; then
      break
    fi
    if [[ "$phase" == "Failed" ]]; then
      kubectl -n "$REGISTRY_TX_NAMESPACE" logs "$REGISTRY_TX_POD_NAME" -c probe >&2 || true
      kubectl -n "$REGISTRY_TX_NAMESPACE" delete pod "$REGISTRY_TX_POD_NAME" --wait=false >/dev/null 2>&1 || true
      echo "registry-tx: probe pod failed" >&2
      return 1
    fi
    sleep 1
  done
  [[ "$phase" == "Succeeded" ]] || {
    kubectl -n "$REGISTRY_TX_NAMESPACE" logs "$REGISTRY_TX_POD_NAME" -c probe >&2 || true
    kubectl -n "$REGISTRY_TX_NAMESPACE" delete pod "$REGISTRY_TX_POD_NAME" --wait=false >/dev/null 2>&1 || true
    echo "registry-tx: probe pod timeout" >&2
    return 1
  }

  out_line="$(kubectl -n "$REGISTRY_TX_NAMESPACE" logs "$REGISTRY_TX_POD_NAME" -c probe 2>/dev/null | tr -d '\r' | awk 'NF{last=$0} END{print last}')"
  kubectl -n "$REGISTRY_TX_NAMESPACE" delete pod "$REGISTRY_TX_POD_NAME" --wait=false >/dev/null 2>&1 || true
  iface="$(awk '{for(i=1;i<=NF;i++) if($i ~ /^iface=/){sub(/^iface=/,"",$i); print $i; exit}}' <<<"$out_line")"
  tx="$(awk '{for(i=1;i<=NF;i++) if($i ~ /^tx_bytes=/){sub(/^tx_bytes=/,"",$i); print $i; exit}}' <<<"$out_line")"
  registry_tx_is_uint "$tx" || {
    echo "registry-tx: invalid tx_bytes probe output: ${out_line:-<empty>}" >&2
    return 1
  }
  printf '%s\t%s\n' "$iface" "$tx"
}
