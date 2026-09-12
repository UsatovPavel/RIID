#!/usr/bin/env bash
# AGENT-117 driver for the Selectel MKS stand (arm-queue.sh is VirtualBox-only).
# Logs go to zOptimization/, same as every AGENT-99 arm since 2026-09-01 - a
# prior version wrote to a /tmp job scratchpad and lost a night's dfdaemon/seed
# logs on session rotation. Usage: selectel-arm-series.sh <arm> [<arm> ...]
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO"
PERF=deploy/k8s/performance
BOOT=deploy/k8s/bootstrap
# Overridable so the same driver runs a porto series: that stand is self-managed
# and writes serverConfig-porto.yaml next to the MKS one, and both may exist at
# the same time.
KC="${RIID_KUBECONFIG:-$REPO/deploy/k8s/providers/cluster/Selectel/serverConfig.yaml}"
export KUBECONFIG="$KC"
ARMS="$*"
STAMP_ROOT="$(date +%Y%m%d-%H%M)"
# Which ticket these artifacts belong to. Hardcoding it once mislabelled an
# AGENT-118 result as agent117, in both the log directory and the TSV name.
AGENT="${AGENT:-agent117}"
LOGROOT="zOptimization/clusterLogs-${AGENT}-${STAMP_ROOT}"
RUNLOG_DIR="$LOGROOT/run-logs"
mkdir -p "$RUNLOG_DIR"

say() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# 2026-09-10: bare-containerd hung on run-pull-scenario.sh for 8h with zero
# progress - only the 08:00 cost-cap cron caught it. Prep steps had timeouts,
# the arm run itself never did. Anchors: 20 images->1h, 100->2h; fit a line
# through both (45s/image + 45min base) so other dataset sizes scale too.
arm_timeout_seconds() {
  local dataset="$PERF/input/dataset_a.tsv" n
  n=$(tail -n +2 "$dataset" 2>/dev/null | grep -c '[^[:space:]]')
  [ "$n" -gt 0 ] 2>/dev/null || n=20
  echo $(( 2700 + 45 * n ))
}

# shellcheck source=scenario/dragonfly-control-plane.inc.sh
source "$PERF/scenario/dragonfly-control-plane.inc.sh"

# Only these arms pull through Dragonfly; bare-* never touches it.
p2p_arm() { case "$1" in riid-*|dfinit-*) return 0 ;; esac; return 1; }

# A dragonfly-client restart bounces containerd, and the riid pods' hostPath
# containerd.sock mount then stays stale until the pod is recreated - waiting
# never fixes it (node ctr works, pod ctr refuses).
restart_riid_pods() {
  say "  rolling riid DaemonSet (its containerd.sock mount goes stale on every restart)"
  kubectl -n riid-system rollout restart daemonset/riid >/dev/null 2>&1
  if kubectl -n riid-system rollout status daemonset/riid --timeout=180s >/dev/null 2>&1; then
    say "  containerd ready"
  else
    say "  WARNING: riid rollout did not complete in 180s, continuing anyway"
  fi
}

# The registry keeps server-side connections of a killed arm and can end up
# pinned at its memory limit, answering /v2/ in minutes instead of milliseconds.
# Three killed runs did that on 2026-09-12 and every later pull hung with no
# error, so probe it from the pull's own netns before spending an arm on it.
registry_reachable() {
  local pod host t
  pod=$(kubectl -n riid-system get pods -l app.kubernetes.io/name=podman-node \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
  [ -n "$pod" ] || { say "  registry probe: no podman-node pod, skipping probe"; return 0; }
  host=$(kubectl -n registry-system get svc local-registry \
    -o jsonpath='{.spec.clusterIP}' 2>/dev/null)
  [ -n "$host" ] || { say "  registry probe: no local-registry ClusterIP, skipping probe"; return 0; }
  t=$(kubectl -n riid-system exec -c installer "$pod" -- nsenter -t 1 -n chroot /host \
    sh -c "timeout 10 curl -s -o /dev/null -w '%{http_code} %{time_total}' http://$host:5000/v2/" 2>/dev/null)
  case "$t" in
    200*|401*) say "  registry probe: $host:5000/v2/ -> $t"; return 0 ;;
    *)         say "  registry probe FAILED: $host:5000/v2/ -> '${t:-timeout}'"; return 1 ;;
  esac
}

# Every measured pull opens with kubectl, so network trouble on the path to the
# API is charged to duration_ms and sets the arm's AGGREGATE: on 2026-09-12
# three pods absorbed 30-140s of TCP connect stalls that way. Probe that path
# first; a stall that only starts mid-arm still has to be caught in the TSV.
api_path_healthy() {
  local server probes worst=0 fails=0 i t0 t1 ms
  probes="${RIID_API_PROBES:-10}"
  server=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}' 2>/dev/null)
  [ -n "$server" ] || { say "  API path probe: no server in $KC, skipping"; return 0; }
  # A whole API round-trip, not a TCP connect: this workstation runs a local
  # TLS-terminating proxy that answers a connect to any address, blackholes
  # included, in 0.1s - so connect timing here measures nothing at all.
  for i in $(seq 1 "$probes"); do
    t0=$(date +%s%N)
    if kubectl get --raw=/livez --request-timeout="${RIID_API_PROBE_TIMEOUT:-5s}" >/dev/null 2>&1; then
      t1=$(date +%s%N); ms=$(( (t1 - t0) / 1000000 ))
      [ "$ms" -gt "$worst" ] && worst=$ms
    else
      fails=$((fails + 1))
    fi
  done
  say "  API path probe: $server, $probes calls, $fails failed, worst ${worst}ms"
  [ "$fails" -eq 0 ] && [ "$worst" -le "${RIID_API_PROBE_MAX_MS:-1500}" ]
}

# One wrapper decides which of the three caches the arm uses: bare-* the engine,
# dfinit-* engine+dragonfly, riid-* all three. The driver no longer encodes that.
clear_for_arm() {
  local arm="$1" stamp="$2" log="$RUNLOG_DIR/${arm}.${stamp}.cache.log"
  say "clearing cache for $arm"
  # A half-done clear is not a warning, it is a dead arm: on 2026-09-11 the RIID
  # pass stopped after 6 of 10 pods on an API timeout, the run went ahead anyway
  # and burned 20 minutes to reach INVALID. Refuse instead of measuring warm.
  if ! make -C "$PERF" clear-cache ARM="$arm" > "$log" 2>&1; then
    say "  clear-cache FAILED - not running this arm. Tail:"
    tail -8 "$log" | sed 's/^/    /'
    return 1
  fi
  say "  nodes reported empty: $(grep -cE 'node containerd images left: 0 ' "$log" 2>/dev/null)"
  say "  riid scratch empty on: $(grep -cE 'riid scratch left: 0 bytes' "$log" 2>/dev/null) pod(s)"
}

# kubectl logs serves only the *current* container log file and kubelet rotates
# it at 10Mi, so AGENT-117 kept 87s of a 427s arm. Dragonfly keeps its own
# rotating copy under /var/log/dragonfly; take that too.
export_file_logs() {
  local ns="$1" pod="$2" container="$3" dest="$4"
  mkdir -p "$dest"
  kubectl -n "$ns" exec "$pod" -c "$container" -- \
    tar cf - -C /var/log/dragonfly . 2>/dev/null | tar xf - -C "$dest" 2>/dev/null
  if [ -z "$(find "$dest" -type f -size +0c -print -quit 2>/dev/null)" ]; then
    rm -rf "$dest"
    say "  no on-disk log from $pod ($container); only the stdout copy survives"
  fi
}

# Repo-relative output instead of a /tmp job scratchpad - the whole reason this
# driver is a committed file and not a scratch script written each session.
export_logs() {
  local arm="$1" stamp="$2" out="$LOGROOT/${arm}.${stamp}"
  mkdir -p "$out"/{dfdaemon,scheduler,seed,riid}
  for p in $(kubectl -n dragonfly-system get pods -l app=dragonfly,component=client -o name 2>/dev/null); do
    kubectl -n dragonfly-system logs "${p#pod/}" -c client --tail=1000000 --timestamps > "$out/dfdaemon/${p#pod/}.log" 2>/dev/null
    export_file_logs dragonfly-system "${p#pod/}" client "$out/dfdaemon-files/${p#pod/}"
  done
  for p in $(kubectl -n dragonfly-system get pods -l app=dragonfly,component=scheduler -o name 2>/dev/null); do
    kubectl -n dragonfly-system logs "${p#pod/}" -c scheduler --tail=1000000 --timestamps > "$out/scheduler/${p#pod/}.log" 2>/dev/null
    export_file_logs dragonfly-system "${p#pod/}" scheduler "$out/scheduler-files/${p#pod/}"
  done
  for p in $(kubectl -n dragonfly-system get pods -l app=dragonfly,component=seed-client -o name 2>/dev/null); do
    kubectl -n dragonfly-system logs "${p#pod/}" -c seed-client --tail=1000000 --timestamps > "$out/seed/${p#pod/}.log" 2>/dev/null
    export_file_logs dragonfly-system "${p#pod/}" seed-client "$out/seed-files/${p#pod/}"
  done
  for p in $(kubectl -n riid-system get pods -l app.kubernetes.io/name=riid -o name 2>/dev/null); do
    kubectl -n riid-system logs "${p#pod/}" -c riid --tail=1000000 --timestamps > "$out/riid/${p#pod/}.log" 2>/dev/null
    kubectl -n riid-system logs "${p#pod/}" -c riid --previous --tail=1000000 --timestamps \
      > "$out/riid/${p#pod/}.previous.log" 2>/dev/null
    [ -s "$out/riid/${p#pod/}.previous.log" ] || rm -f "$out/riid/${p#pod/}.previous.log"
    kubectl -n riid-system describe pod "${p#pod/}" > "$out/riid/${p#pod/}.describe.txt" 2>/dev/null
  done
  # The cache-clear output is the only evidence the arm started cold, and
  # validate-arm.sh only ever sees this directory - without the copy the gate
  # cannot tell a cold start from a node that kept the whole dataset.
  cp "$RUNLOG_DIR/${arm}.${stamp}.cache.log" "$out/cache-clear.log" 2>/dev/null || true
  kubectl -n riid-system get events --sort-by=.lastTimestamp > "$out/riid/events.txt" 2>/dev/null
  kubectl get nodes -o wide > "$out/riid/nodes.txt" 2>/dev/null
  { echo "# $arm"; echo "captured: $(date -Is)"; echo;
    find "$out" -type f \( -name '*.log*' -o -path '*-files/*' \) -printf '%p %s bytes\n' | sort; } > "$out/README.md"
  ( cd "$out" && find . -type f \( -name '*.log*' -o -path '*-files/*' \) -exec sha256sum {} + > SHA256SUMS 2>/dev/null )
  say "  logs exported to $out"
}

# Before the series, not per arm: a stand where p2p serves nothing produces a
# full matrix of numbers that all mean the same thing. SKIP_P2P_SMOKE=1 is for
# re-running a single arm on a stand already proven this session.
if [ "${SKIP_P2P_SMOKE:-0}" != 1 ]; then
  say "=== p2p smoke ==="
  smoke_engine="${ARMS##*-}"
  if ENGINE="$smoke_engine" bash "$PERF/scenario/p2p-smoke.sh" \
       > "$RUNLOG_DIR/p2p-smoke.${STAMP_ROOT}.log" 2>&1; then
    say "  p2p smoke OK"
  else
    say "  p2p smoke FAILED - not running the series. Tail:"
    tail -6 "$RUNLOG_DIR/p2p-smoke.${STAMP_ROOT}.log" | sed 's/^/    /'
    exit 1
  fi
fi

for arm in $ARMS; do
  stamp=$(date +%Y%m%d-%H%M)
  say "=== $arm ($stamp) ==="
  # Before the cache clear, which needs the same path and would be wasted.
  if ! api_path_healthy; then
    say "$arm: SKIPPED - the API path is unstable, an arm run now would measure it"
    say "  if this stand's address is routed into a tunnel: make -C <tf module> bypass-api"
    continue
  fi
  if ! clear_for_arm "$arm" "$stamp"; then
    say "$arm: SKIPPED - cache not cleared"
    continue
  fi
  say "preparing Dragonfly and the riid pods"
  roll_data_plane=0
  p2p_arm "$arm" && roll_data_plane=1
  dragonfly_prepare "$roll_data_plane"
  restart_riid_pods
  case "$arm" in dfinit-*)
    engine="${arm#dfinit-}"
    say "dfinit-enable ENGINE=$engine"
    if ! make -C "$BOOT" dfinit-enable ENGINE="$engine" > "$RUNLOG_DIR/${arm}.${stamp}.dfinit-enable.log" 2>&1; then
      say "  dfinit-enable FAILED - tail:"
      tail -15 "$RUNLOG_DIR/${arm}.${stamp}.dfinit-enable.log" | sed 's/^/    /'
      continue
    fi
    # The data plane was re-pointed above; this pass only prunes what dfinit-enable left.
    say "preparing Dragonfly again after dfinit-enable"
    dragonfly_prepare 0
    restart_riid_pods;;
  esac
  if ! registry_reachable; then
    say "$arm: SKIPPED - the registry is not answering, restart local-registry first"
    continue
  fi
  if p2p_arm "$arm" && ! dragonfly_wait_settled; then
    say "$arm: SKIPPED - P2P control plane not settled"
    continue
  fi
  tsv="$PERF/output/${arm}.tsv"
  before=$(stat -c %Y "$tsv" 2>/dev/null || echo 0)
  arm_timeout=$(arm_timeout_seconds)
  say "running arm (timeout ${arm_timeout}s)"
  timeout -k 30s "${arm_timeout}s" make -C "$PERF" "$arm" > "$RUNLOG_DIR/${arm}.${stamp}.run.log" 2>&1; rc=$?
  if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
    say "  arm TIMED OUT after ${arm_timeout}s - sweeping leftover children"
    pkill -9 -f "run-pull-scenario.sh" 2>/dev/null || true
  fi
  after=$(stat -c %Y "$tsv" 2>/dev/null || echo 0)
  export_logs "$arm" "$stamp"
  case "$arm" in dfinit-*) make -C "$BOOT" dfinit-disable > "$RUNLOG_DIR/${arm}.${stamp}.dfinit-disable.log" 2>&1 || true;; esac
  if [ "$rc" -ne 0 ] || [ "$after" = "$before" ]; then
    say "$arm: RUN FAILED (rc=$rc, tsv rewritten=$([ "$after" != "$before" ] && echo yes || echo no))"
    tail -5 "$RUNLOG_DIR/${arm}.${stamp}.run.log" | sed 's/^/    /'
    continue
  fi
  # Only stamp a result AFTER validate-arm confirms it - a cp before
  # validation once put an INVALID run in output/ under the same naming as a
  # real result, indistinguishable without re-reading a log that no longer exists.
  if bash "$PERF/summarize/validate-arm.sh" "$arm" "$tsv" "$LOGROOT/${arm}.${stamp}" 2>&1 | sed 's/^/  /' | tee /dev/stderr | grep -q ": VALID$"; then
    cp "$tsv" "$PERF/output/${arm}.${AGENT}-${stamp}.tsv"
    say "  saved as ${arm}.${AGENT}-${stamp}.tsv"
  else
    say "  NOT saved - validate-arm rejected this run"
  fi
done
say "series done - logs under $LOGROOT"
