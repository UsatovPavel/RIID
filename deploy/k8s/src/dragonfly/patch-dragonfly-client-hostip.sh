#!/usr/bin/env bash
# Pins the advertised P2P IP for the dragonfly-client DaemonSet: with hostNetwork
# dfdaemon advertises its default-route IP, which on cloned-NAT stands is the same
# unreachable address on every node, so P2P falls back to the registry. Splices the
# per-node status.hostIP in. Not for seed-client - it does not run hostNetwork.
set -euo pipefail

NS="${DRAGONFLY_NAMESPACE:-dragonfly-system}"
DS=dragonfly-client
CONTAINER=client
BIN=/usr/local/bin/dfdaemon

if ! kubectl -n "$NS" get daemonset "$DS" >/dev/null 2>&1; then
  echo "patch-dragonfly-client-hostip: no $DS daemonset in $NS (client.enable=false?) — skipping" >&2
  exit 0
fi

# Reuse whatever args Helm rendered instead of hardcoding them. On a re-run the live
# args are OUR OWN wrapper, not Helm's, so reading them again nests the wrapper once
# per apply and eventually hands dfdaemon its shell words as flags ("unexpected
# argument 'set'"). Remember the real ones in an annotation the first time.
ARGS_ANNOTATION="riid-hostip-original-args"
SAVED_ARGS="$(kubectl -n "$NS" get daemonset "$DS" \
  -o jsonpath="{.metadata.annotations['${ARGS_ANNOTATION}']}" 2>/dev/null || true)"
if [ -n "$SAVED_ARGS" ]; then
  mapfile -t ORIG_ARGS < <(printf '%s' "$SAVED_ARGS" | tr ' ' '\n')
else
  mapfile -t ORIG_ARGS < <(kubectl -n "$NS" get daemonset "$DS" \
    -o jsonpath='{.spec.template.spec.containers[0].args[*]}' | tr ' ' '\n')
  kubectl -n "$NS" annotate daemonset "$DS" \
    "${ARGS_ANNOTATION}=${ORIG_ARGS[*]}" --overwrite >/dev/null
fi
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

# The chart mounts the socket dir from /var/run/dragonfly, and in the container
# /var/run is a symlink to /run - a ~873 MB tmpfs here. dfdaemon's storage.dir under
# it caps the P2P cache by RAM: it fills, evicts, peers serve nothing (a full /run
# also blocks every container start). Move it to disk; RIID must mount the same path.
SOCK_HOSTPATH="${SOCK_HOSTPATH:-/var/lib/dragonfly-run}"
SOCK_IDX="$(kubectl -n "$NS" get ds "$DS" -o json \
  | python3 -c 'import json,sys; v=json.load(sys.stdin)["spec"]["template"]["spec"]["volumes"]; print(next((i for i,x in enumerate(v) if x["name"]=="socket-dir"), -1))')"
if [ "${SOCK_IDX}" != "-1" ]; then
  echo ">>> patch-dragonfly-client-hostip: socket dir -> ${SOCK_HOSTPATH} (off tmpfs)"
  kubectl -n "$NS" patch daemonset "$DS" --type json \
    -p "[{\"op\":\"replace\",\"path\":\"/spec/template/spec/volumes/${SOCK_IDX}/hostPath/path\",\"value\":\"${SOCK_HOSTPATH}\"}]" >/dev/null
fi

echo ">>> patch-dragonfly-client-hostip: pinning host.ip to status.hostIP on $DS/$CONTAINER"
kubectl -n "$NS" patch daemonset "$DS" --type strategic -p "$PATCH"
kubectl -n "$NS" rollout status daemonset "$DS" --timeout=5m
