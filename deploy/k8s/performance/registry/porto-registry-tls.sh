#!/usr/bin/env bash
# TLS entry for bare-porto: Porto 5.3.58 fetches blobs over https only, whatever
# docker_insecure_registry says. Issues an IP-SAN cert for the registry ClusterIP,
# stores it as a Secret, trusts the CA on every node, checks https from host netns.
set -euo pipefail

NS_REG="${REGISTRY_NAMESPACE:-registry-system}"
NS_NODE="${RIID_NAMESPACE:-riid-system}"
TLS_PORT="${REGISTRY_TLS_PORT:-5443}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

ip="$(kubectl -n "$NS_REG" get svc local-registry -o jsonpath='{.spec.clusterIP}')"
[ -n "$ip" ] || { echo "porto-registry-tls: no ClusterIP for local-registry" >&2; exit 1; }

openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=riid-bench-registry-ca" \
  -keyout "$work/ca.key" -out "$work/ca.crt" 2>/dev/null
openssl req -newkey rsa:2048 -nodes -subj "/CN=$ip" -keyout "$work/tls.key" -out "$work/tls.csr" 2>/dev/null
printf 'subjectAltName=IP:%s\nextendedKeyUsage=serverAuth\n' "$ip" >"$work/ext.cnf"
openssl x509 -req -in "$work/tls.csr" -CA "$work/ca.crt" -CAkey "$work/ca.key" -CAcreateserial \
  -days 30 -extfile "$work/ext.cnf" -out "$work/tls.crt" 2>/dev/null

kubectl -n "$NS_REG" create secret tls local-registry-tls --cert="$work/tls.crt" --key="$work/tls.key" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS_REG" rollout restart deployment/local-registry
kubectl -n "$NS_REG" rollout status deployment/local-registry --timeout=300s

ca_b64="$(base64 -w0 "$work/ca.crt")"
failed=0
for pod in $(kubectl -n "$NS_NODE" get pods -l app.kubernetes.io/name=podman-node -o jsonpath='{.items[*].metadata.name}'); do
  if ! kubectl -n "$NS_NODE" exec -c installer "$pod" -- nsenter -t 1 -n chroot /host \
      env CA="$ca_b64" URL="https://$ip:$TLS_PORT/v2/" sh -ec '
        printf "%s" "$CA" | base64 -d > /usr/local/share/ca-certificates/riid-bench-registry-ca.crt
        update-ca-certificates >/dev/null 2>&1
        for i in $(seq 1 30); do wget -q --spider "$URL" 2>/dev/null && break; sleep 2; done
        wget -q --spider "$URL" && echo "$(cat /etc/hostname): $URL trusted"'; then
    echo "porto-registry-tls: FAILED on $pod" >&2
    failed=1
  fi
done
[ "$failed" -eq 0 ] && echo "porto-registry-tls: ready, PORTO_REGISTRY_HOST=$ip:$TLS_PORT"
exit "$failed"
