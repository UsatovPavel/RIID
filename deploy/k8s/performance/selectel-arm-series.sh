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
KC="$REPO/deploy/k8s/providers/cluster/Selectel/serverConfig.yaml"
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

# cache-clear/dfinit-enable restart dragonfly-client, which bounces
# containerd; riid pods' hostPath containerd.sock mount then goes stale
# forever (confirmed: node ctr works, pod ctr refuses, recreating the pod
# fixes it - waiting never does).
wait_for_containerd() {
  say "  rolling riid DaemonSet (its containerd.sock mount goes stale on every restart)"
  kubectl -n riid-system rollout restart daemonset/riid >/dev/null 2>&1
  if kubectl -n riid-system rollout status daemonset/riid --timeout=180s >/dev/null 2>&1; then
    say "  containerd ready"
  else
    say "  WARNING: riid rollout did not complete in 180s, continuing anyway"
  fi
}

# Every scheduler restart inserts a new manager.scheduler row and never
# cleans up the old one (11 accumulated once, crashlooping every client
# before it reached the live entry). The manager caches this list at its own
# startup, so purging the DB alone does nothing until it restarts again.
purge_stale_scheduler_rows() {
  local live_ip
  live_ip=$(kubectl -n dragonfly-system get pod dragonfly-scheduler-0 -o jsonpath='{.status.podIP}' 2>/dev/null)
  [ -n "$live_ip" ] || { say "  purge_stale_scheduler_rows: no live scheduler IP, skipping"; return; }
  kubectl -n dragonfly-system exec dragonfly-mysql-0 -- \
    mysql -uroot -pdragonfly-root -e "DELETE FROM manager.scheduler WHERE ip != '$live_ip';" >/dev/null 2>&1
  say "  scheduler table pruned to live IP $live_ip - restarting manager to pick it up"
  local before_restarts
  before_restarts=$(kubectl -n dragonfly-system get pod dragonfly-scheduler-0 \
    -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null || echo 0)
  kubectl -n dragonfly-system rollout restart deployment/dragonfly-manager >/dev/null 2>&1
  kubectl -n dragonfly-system rollout status deployment/dragonfly-manager --timeout=120s >/dev/null 2>&1
  # The scheduler dies with the manager: announcer.New dials manager:65003 at
  # startup and exits 1 on timeout. On 2026-09-12 it did exactly that 4 min into
  # dfinit-podman, came back on a new pod IP, and left ten dfdaemons wedged on
  # the dead one - ten podman pulls hung 35 min with no error.
  kubectl -n dragonfly-system rollout status statefulset/dragonfly-scheduler --timeout=300s >/dev/null 2>&1
  local after_restarts new_ip
  after_restarts=$(kubectl -n dragonfly-system get pod dragonfly-scheduler-0 \
    -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null || echo 0)
  new_ip=$(kubectl -n dragonfly-system get pod dragonfly-scheduler-0 -o jsonpath='{.status.podIP}' 2>/dev/null)
  if [ "$after_restarts" != "$before_restarts" ] || [ "$new_ip" != "$live_ip" ]; then
    say "  scheduler restarted with the manager (restarts $before_restarts->$after_restarts, ip $live_ip->$new_ip)"
    kubectl -n dragonfly-system exec dragonfly-mysql-0 -- \
      mysql -uroot -pdragonfly-root -e "DELETE FROM manager.scheduler WHERE ip != '$new_ip';" >/dev/null 2>&1
    restart_dragonfly_data_plane
    return
  fi
  local ok=0
  for _ in $(seq 1 20); do
    local total ready
    total=$(kubectl -n dragonfly-system get pods -l app=dragonfly,component=client --no-headers 2>/dev/null | wc -l)
    ready=$(kubectl -n dragonfly-system get pods -l app=dragonfly,component=client --no-headers 2>/dev/null | awk '$2=="1/1"' | wc -l)
    [ "$total" -gt 0 ] && [ "$total" = "$ready" ] && { ok=1; break; }
    sleep 5
  done
  [ "$ok" = 1 ] && say "  dragonfly-client stable" || say "  WARNING: dragonfly-client not all Ready after 100s, continuing anyway"
}

# A dfdaemon keeps talking to the scheduler pod IP it first connected to, so a
# scheduler that moved leaves every client stuck there until it is bounced.
restart_dragonfly_data_plane() {
  say "  bouncing the data plane so no client keeps the dead scheduler address"
  kubectl -n dragonfly-system rollout restart daemonset/dragonfly-client >/dev/null 2>&1
  kubectl -n dragonfly-system rollout restart statefulset/dragonfly-seed-client >/dev/null 2>&1
  kubectl -n dragonfly-system rollout status daemonset/dragonfly-client --timeout=300s >/dev/null 2>&1
  kubectl -n dragonfly-system rollout status statefulset/dragonfly-seed-client --timeout=300s >/dev/null 2>&1
  local rows
  rows=$(kubectl -n dragonfly-system exec dragonfly-mysql-0 -- \
    mysql -uroot -pdragonfly-root -N -e \
    "SELECT ip FROM manager.scheduler WHERE state='active';" 2>/dev/null | tr -d '\r')
  say "  active scheduler rows after the bounce: $(echo "$rows" | tr '\n' ' ')"
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

# A podman pull through the dfinit mirror blocks forever - no error, no timeout -
# if the scheduler moves after the pull began: on 2026-09-12 ten pulls sat 35 min
# with zero dfdaemon tasks. So the control plane must be final before an arm runs,
# not merely "all clients Ready", which it was in both hung runs.
wait_p2p_settled() {
  local tries="${1:-60}" stable=0 prev_ip="" prev_restarts=""
  while [ "$tries" -gt 0 ]; do
    tries=$((tries - 1))
    local ip restarts ready total active
    ip=$(kubectl -n dragonfly-system get pod dragonfly-scheduler-0 \
      -o jsonpath='{.status.podIP}' 2>/dev/null)
    restarts=$(kubectl -n dragonfly-system get pod dragonfly-scheduler-0 \
      -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null)
    ready=$(kubectl -n dragonfly-system get pods --no-headers 2>/dev/null | awk '$2=="1/1"' | wc -l)
    total=$(kubectl -n dragonfly-system get pods --no-headers 2>/dev/null | wc -l)
    active=$(kubectl -n dragonfly-system exec dragonfly-mysql-0 -- mysql -uroot -pdragonfly-root -N \
      -e "SELECT ip FROM manager.scheduler WHERE state='active';" 2>/dev/null | tr -d '\r' | tr '\n' ' ')
    active="${active% }"
    if [ -n "$ip" ] && [ "$total" -gt 0 ] && [ "$ready" = "$total" ] \
       && [ "$active" = "$ip" ] && [ "$ip" = "$prev_ip" ] && [ "$restarts" = "$prev_restarts" ]; then
      stable=$((stable + 1))
      if [ "$stable" -ge 3 ]; then
        say "  P2P settled: scheduler $ip, $ready/$total dragonfly pods Ready"
        return 0
      fi
    else
      stable=0
    fi
    prev_ip="$ip"; prev_restarts="$restarts"
    sleep 10
  done
  say "  P2P did not settle (scheduler still moving or manager row stale)"
  return 1
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

for arm in $ARMS; do
  stamp=$(date +%Y%m%d-%H%M)
  say "=== $arm ($stamp) ==="
  if ! clear_for_arm "$arm" "$stamp"; then
    say "$arm: SKIPPED - cache not cleared"
    continue
  fi
  say "pruning stale scheduler rows and waiting for containerd"
  purge_stale_scheduler_rows
  wait_for_containerd
  case "$arm" in dfinit-*)
    engine="${arm#dfinit-}"
    say "dfinit-enable ENGINE=$engine"
    if ! make -C "$BOOT" dfinit-enable ENGINE="$engine" > "$RUNLOG_DIR/${arm}.${stamp}.dfinit-enable.log" 2>&1; then
      say "  dfinit-enable FAILED - tail:"
      tail -15 "$RUNLOG_DIR/${arm}.${stamp}.dfinit-enable.log" | sed 's/^/    /'
      continue
    fi
    say "pruning stale scheduler rows and waiting for containerd"
    purge_stale_scheduler_rows
    wait_for_containerd;;
  esac
  if ! registry_reachable; then
    say "$arm: SKIPPED - the registry is not answering, restart local-registry first"
    continue
  fi
  # bare-* never touches Dragonfly, so only the P2P arms need the settle gate.
  case "$arm" in
    riid-*|dfinit-*)
      if ! wait_p2p_settled; then
        say "$arm: SKIPPED - P2P control plane not settled"
        continue
      fi ;;
  esac
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
