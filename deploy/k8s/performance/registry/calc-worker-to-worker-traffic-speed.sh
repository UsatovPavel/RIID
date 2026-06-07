#!/usr/bin/env bash
set -euo pipefail

# Measure worker-to-worker traffic speed by downloading a generated file
# from one pod (HTTP server) to another pod (HTTP client) on different workers.
#
# Env overrides:
#   TEST_NAMESPACE   - namespace for temporary pods/service (default: riid-system)
#   FILE_SIZE_MB     - generated file size in MiB (default: 1024)
#   ITERATIONS       - number of random worker-pair tests (default: 10)
#   SERVER_IMAGE     - image for server pod (default: busybox:1.36.1)
#   CLIENT_IMAGE     - image for client pod (default: curlimages/curl:8.12.1)
#   SERVICE_PORT     - HTTP port (default: 8080)
#   WAIT_TIMEOUT     - kubectl wait timeout (default: 300s)
#   KEEP_RESOURCES   - if "1", do not cleanup test resources

NS="${TEST_NAMESPACE:-riid-system}"
FILE_SIZE_MB="${FILE_SIZE_MB:-1024}"
ITERATIONS="${ITERATIONS:-10}"
SERVER_IMAGE="${SERVER_IMAGE:-busybox:1.36.1}"
CLIENT_IMAGE="${CLIENT_IMAGE:-curlimages/curl:8.12.1}"
SERVICE_PORT="${SERVICE_PORT:-8080}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-300s}"
KEEP_RESOURCES="${KEEP_RESOURCES:-0}"
SERVER_WARMUP_RETRIES="${SERVER_WARMUP_RETRIES:-120}"
SERVER_WARMUP_SLEEP_SEC="${SERVER_WARMUP_SLEEP_SEC:-1}"

FILE_NAME="test-${FILE_SIZE_MB}m.bin"
CURRENT_SERVER_POD=""
CURRENT_CLIENT_POD=""
CURRENT_SERVICE_NAME=""
RESULTS_FILE="$(mktemp)"

cleanup_iteration() {
  local service_name="$1"
  local server_pod="$2"
  local client_pod="$3"

  if [[ "$KEEP_RESOURCES" == "1" ]]; then
    echo "cleanup: skipped (KEEP_RESOURCES=1)"
    return
  fi
  kubectl -n "$NS" delete svc "$service_name" --ignore-not-found >/dev/null 2>&1 || true
  kubectl -n "$NS" delete pod "$server_pod" "$client_pod" --ignore-not-found >/dev/null 2>&1 || true
}

cleanup_all() {
  if [[ -n "$CURRENT_SERVICE_NAME" || -n "$CURRENT_SERVER_POD" || -n "$CURRENT_CLIENT_POD" ]]; then
    cleanup_iteration "$CURRENT_SERVICE_NAME" "$CURRENT_SERVER_POD" "$CURRENT_CLIENT_POD"
  fi
  rm -f "$RESULTS_FILE" >/dev/null 2>&1 || true
}
trap cleanup_all EXIT

if ! command -v kubectl >/dev/null 2>&1; then
  echo "kubectl not found" >&2
  exit 2
fi

if ! kubectl get namespace "$NS" >/dev/null 2>&1; then
  echo "namespace not found: $NS" >&2
  exit 2
fi

if ! [[ "$FILE_SIZE_MB" =~ ^[1-9][0-9]*$ ]]; then
  echo "FILE_SIZE_MB must be a positive integer, got: $FILE_SIZE_MB" >&2
  exit 2
fi

if ! [[ "$ITERATIONS" =~ ^[1-9][0-9]*$ ]]; then
  echo "ITERATIONS must be a positive integer, got: $ITERATIONS" >&2
  exit 2
fi

mapfile -t workers < <(kubectl get nodes -l '!node-role.kubernetes.io/control-plane,!node-role.kubernetes.io/master' -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
if ((${#workers[@]} < 2)); then
  echo "need at least 2 worker nodes (found ${#workers[@]})" >&2
  exit 2
fi

echo "namespace=$NS"
echo "workers_count=${#workers[@]}"
echo "file_size_mb=$FILE_SIZE_MB"
echo "iterations=$ITERATIONS"

for iter in $(seq 1 "$ITERATIONS"); do
  n_workers="${#workers[@]}"
  server_idx=$((RANDOM % n_workers))
  client_idx=$((RANDOM % (n_workers - 1)))
  if ((client_idx >= server_idx)); then
    client_idx=$((client_idx + 1))
  fi

  server_node="${workers[$server_idx]}"
  client_node="${workers[$client_idx]}"
  SERVICE_NAME="w2w-speed-${iter}"
  SERVER_POD="w2w-speed-server-${iter}"
  CLIENT_POD="w2w-speed-client-${iter}"
  CURRENT_SERVICE_NAME="$SERVICE_NAME"
  CURRENT_SERVER_POD="$SERVER_POD"
  CURRENT_CLIENT_POD="$CLIENT_POD"

  echo
  echo "iteration=$iter"
  echo "server_node=$server_node"
  echo "client_node=$client_node"

  cleanup_iteration "$SERVICE_NAME" "$SERVER_POD" "$CLIENT_POD"

  kubectl -n "$NS" apply -f - <<EOF >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: ${SERVER_POD}
  labels:
    app: ${SERVICE_NAME}
spec:
  nodeName: ${server_node}
  restartPolicy: Never
  containers:
    - name: server
      image: ${SERVER_IMAGE}
      command: ["sh", "-ec"]
      args:
        - |
          mkdir -p /srv
          if [ ! -f "/srv/${FILE_NAME}" ]; then
            dd if=/dev/zero of="/srv/${FILE_NAME}" bs=1M count=${FILE_SIZE_MB}
          fi
          httpd -f -p ${SERVICE_PORT} -h /srv
---
apiVersion: v1
kind: Pod
metadata:
  name: ${CLIENT_POD}
spec:
  nodeName: ${client_node}
  restartPolicy: Never
  containers:
    - name: client
      image: ${CLIENT_IMAGE}
      command: ["sh", "-ec", "sleep 36000"]
---
apiVersion: v1
kind: Service
metadata:
  name: ${SERVICE_NAME}
spec:
  selector:
    app: ${SERVICE_NAME}
  ports:
    - port: ${SERVICE_PORT}
      targetPort: ${SERVICE_PORT}
EOF

  kubectl -n "$NS" wait --for=condition=Ready "pod/${SERVER_POD}" --timeout="$WAIT_TIMEOUT" >/dev/null
  kubectl -n "$NS" wait --for=condition=Ready "pod/${CLIENT_POD}" --timeout="$WAIT_TIMEOUT" >/dev/null

  download_url="http://${SERVICE_NAME}.${NS}.svc.cluster.local:${SERVICE_PORT}/${FILE_NAME}"
  echo "download_url=$download_url"

  echo "waiting_server_ready=true retries=${SERVER_WARMUP_RETRIES} sleep_sec=${SERVER_WARMUP_SLEEP_SEC}"
  server_ready=0
  for _ in $(seq 1 "$SERVER_WARMUP_RETRIES"); do
    if kubectl -n "$NS" exec "$CLIENT_POD" -- sh -ec "curl -fsS -o /dev/null '$download_url'" >/dev/null 2>&1; then
      server_ready=1
      break
    fi
    sleep "$SERVER_WARMUP_SLEEP_SEC"
  done

  if ((server_ready == 0)); then
    echo "server did not become reachable in time: $download_url" >&2
    echo "--- server pod logs ---" >&2
    kubectl -n "$NS" logs "$SERVER_POD" --tail=200 >&2 || true
    exit 1
  fi

  curl_out="$(
    kubectl -n "$NS" exec "$CLIENT_POD" -- sh -ec \
      "curl -o /dev/null -sS -w 'time_total=%{time_total}\nspeed_bps=%{speed_download}\nsize_bytes=%{size_download}\n' '$download_url'"
  )"

  time_total="$(awk -F '=' '$1=="time_total"{print $2}' <<<"$curl_out")"
  speed_bps="$(awk -F '=' '$1=="speed_bps"{print $2}' <<<"$curl_out")"
  size_bytes="$(awk -F '=' '$1=="size_bytes"{print $2}' <<<"$curl_out")"

  if [[ -z "$time_total" || -z "$speed_bps" || -z "$size_bytes" ]]; then
    echo "failed to parse curl output:" >&2
    echo "$curl_out" >&2
    exit 1
  fi

  speed_mbit_s="$(awk -v bps="$speed_bps" 'BEGIN{printf "%.2f", (bps * 8) / 1000000}')"
  speed_mib_s="$(awk -v bps="$speed_bps" 'BEGIN{printf "%.2f", bps / (1024 * 1024)}')"
  size_mib="$(awk -v b="$size_bytes" 'BEGIN{printf "%.2f", b / (1024 * 1024)}')"

  echo "result:"
  echo "  downloaded_bytes=$size_bytes"
  echo "  downloaded_mib=$size_mib"
  echo "  time_total_s=$time_total"
  echo "  speed_bytes_s=$speed_bps"
  echo "  speed_mib_s=$speed_mib_s"

  printf "%s\t%s\t%s\t%s\t%s\t%s\n" \
    "$iter" "$server_node" "$client_node" "$time_total" "$speed_bps" "$size_bytes" >>"$RESULTS_FILE"

  cleanup_iteration "$SERVICE_NAME" "$SERVER_POD" "$CLIENT_POD"
  CURRENT_SERVICE_NAME=""
  CURRENT_SERVER_POD=""
  CURRENT_CLIENT_POD=""
done

echo
echo "summary:"
awk -F '\t' '
BEGIN{
  min=-1
  max=0
  sum=0
  cnt=0
}
{
  bps=$5+0
  if (min<0 || bps<min) min=bps
  if (bps>max) max=bps
  sum+=bps
  cnt++
}
END{
  if (cnt==0) {
    print "  no iterations completed"
    exit
  }
  avg=sum/cnt
  printf "  iterations_done=%d\n", cnt
  printf "  avg_speed_mib_s=%.2f\n", avg/(1024*1024)
  printf "  min_speed_mib_s=%.2f\n", min/(1024*1024)
  printf "  max_speed_mib_s=%.2f\n", max/(1024*1024)
}
' "$RESULTS_FILE"
