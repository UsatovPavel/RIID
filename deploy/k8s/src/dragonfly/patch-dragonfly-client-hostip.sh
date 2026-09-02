#!/usr/bin/env bash
# Pins the advertised P2P IP for the dragonfly-client DaemonSet.
#
# Why: dragonfly-client runs with hostNetwork: true; dfdaemon's own outbound-
# route IP autodetection then picks whichever interface owns the default
# route. On a stand where every node clones the same VirtualBox NAT adapter
# (e.g. 10.0.2.15 on enp0s3) ahead of the real cluster network (e.g.
# 192.168.56.0/24 on enp0s8), every dragonfly-client pod advertises the
# identical, mutually-unreachable NAT address as its peer host_id: every P2P
# fetch then times out (~67s DEADLINE_EXCEEDED) and RIID silently falls back
# to the registry. This is the same class of bug already fixed for Calico via
# IP_AUTODETECTION_METHOD=cidr=..., but Dragonfly's upstream Helm chart
# (dragonflyoss/dragonfly) has no per-node hostNetwork "IP()" knob or command
# override for the client DaemonSet.
#
# dfdaemon.yaml's `host.ip` field is real ("the advertise ip of the host",
# see https://d7y.io/docs/next/reference/configuration/client/dfdaemon/) but
# is rendered once per `helm upgrade` into a single ConfigMap shared by every
# DaemonSet pod, so it cannot hold a literal per-node value. The pod's own
# status.hostIP (Kubernetes downward API) is per-node and always correct
# (it's the node's InternalIP — harmless/unaffected on a stand with a single
# NIC per node, e.g. Selectel). This script overrides only the client
# container's command to splice status.hostIP into a private copy of the
# mounted config before exec'ing the real dfdaemon binary with whatever args
# Helm rendered.
#
# NOT applied to dragonfly-seed-client: that StatefulSet does not run
# hostNetwork, so its own host_id already correctly advertises its pod IP
# (verified in its logs — reachable over the CNI overlay). Patching it the
# same way would advertise the *node* IP for a pod only reachable at its
# *pod* IP, breaking it instead of fixing it.
#
# Idempotent; safe to call after every `helm upgrade --install dragonfly`
# (install-dragonfly.sh does, including the dfinit-enable/disable path) —
# each Helm upgrade re-renders the DaemonSet from the chart (undoing this
# patch), so it must be re-applied every time, not just once.
set -euo pipefail

NS="${DRAGONFLY_NAMESPACE:-dragonfly-system}"
DS=dragonfly-client
CONTAINER=client
BIN=/usr/local/bin/dfdaemon

if ! kubectl -n "$NS" get daemonset "$DS" >/dev/null 2>&1; then
  echo "patch-dragonfly-client-hostip: no $DS daemonset in $NS (client.enable=false?) — skipping" >&2
  exit 0
fi

# Reuse whatever args Helm rendered (log-level / --console) instead of
# hardcoding them, so a scripts/values.yaml change doesn't need a matching
# edit here.
mapfile -t ORIG_ARGS < <(kubectl -n "$NS" get daemonset "$DS" \
  -o jsonpath='{.spec.template.spec.containers[0].args[*]}' | tr ' ' '\n')
EXEC_ARGS=""
for a in "${ORIG_ARGS[@]}"; do
  [ -n "$a" ] || continue
  EXEC_ARGS="$EXEC_ARGS $(printf '%q' "$a")"
done

WRAPPER=$(cat <<EOF
set -e
cp /etc/dragonfly/dfdaemon.yaml /tmp/dfdaemon.yaml
if grep -q '^  ip:' /tmp/dfdaemon.yaml; then
  sed -i "s#^  ip:.*#  ip: \"\$RIID_HOST_IP\"#" /tmp/dfdaemon.yaml
else
  sed -i "/^  schedulerClusterID:/a\\  ip: \"\$RIID_HOST_IP\"" /tmp/dfdaemon.yaml
fi
exec $BIN -c /tmp/dfdaemon.yaml$EXEC_ARGS
EOF
)

WRAPPER_JSON="$(printf '%s' "$WRAPPER" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')"

PATCH=$(cat <<JSON
{"spec":{"template":{"spec":{"containers":[{"name":"$CONTAINER","command":["/bin/sh","-c"],"args":[$WRAPPER_JSON],"env":[{"name":"RIID_HOST_IP","valueFrom":{"fieldRef":{"fieldPath":"status.hostIP"}}}]}]}}}}
JSON
)

echo ">>> patch-dragonfly-client-hostip: pinning host.ip to status.hostIP on $DS/$CONTAINER"
kubectl -n "$NS" patch daemonset "$DS" --type strategic -p "$PATCH"
kubectl -n "$NS" rollout status daemonset "$DS" --timeout=5m
