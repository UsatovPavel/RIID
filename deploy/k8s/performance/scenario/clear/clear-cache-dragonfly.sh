#!/usr/bin/env bash
# Dragonfly's cache only: the dfdaemon stores plus the control-plane state that
# indexes them. Not the engine store (clear-cache-engines.sh) and not RIID's
# scratch (clear-cache-riid.sh) - each of the three is cleared by its own script.
# Four stages below, in order; each reports what it did and sets failed.

# storage.dir differs per component (client /var/run/dragonfly/data, seed
# /var/lib/dragonfly), so it is read from each pod's own dfdaemon.yaml. Content
# and control-plane state reset together: with content left behind the scheduler
# no longer knows about it and every client goes back to source.

# Env: DRAGONFLY_NAMESPACE, DRAGONFLY_CACHE_DIRS, DRAGONFLY_CONTAINER,
#   DRAGONFLY_RESET_CONTROL_PLANE / _RESTART_DATA_PLANE / _RECREATE_REDIS_STATE
#   (1/0, all default 1), DRAGONFLY_ROLLOUT_TIMEOUT (default 5m)
set -euo pipefail

. "$(dirname "${BASH_SOURCE[0]}")/kubectl-retry.inc.sh"

DFS="${DRAGONFLY_NAMESPACE:-dragonfly-system}"
DRAGONFLY_CACHE_DIRS="${DRAGONFLY_CACHE_DIRS:-/var/cache/dragonfly /var/run/dragonfly/output /var/run/dragonfly/data /var/lib/dragonfly}"
DRAGONFLY_RESET_CONTROL_PLANE="${DRAGONFLY_RESET_CONTROL_PLANE:-1}"
DRAGONFLY_RESTART_DATA_PLANE="${DRAGONFLY_RESTART_DATA_PLANE:-1}"
DRAGONFLY_CLIENT_DS="${DRAGONFLY_CLIENT_DS:-daemonset/dragonfly-client}"
DRAGONFLY_SEED_STS="${DRAGONFLY_SEED_STS:-statefulset/dragonfly-seed-client}"
DRAGONFLY_RECREATE_REDIS_STATE="${DRAGONFLY_RECREATE_REDIS_STATE:-1}"
DRAGONFLY_MANAGER_RESOURCE="${DRAGONFLY_MANAGER_RESOURCE:-deployment/dragonfly-manager}"
DRAGONFLY_SCHEDULER_RESOURCE="${DRAGONFLY_SCHEDULER_RESOURCE:-statefulset/dragonfly-scheduler}"
DRAGONFLY_ROLLOUT_TIMEOUT="${DRAGONFLY_ROLLOUT_TIMEOUT:-5m}"
DRAGONFLY_REDIS_MASTER_STS="${DRAGONFLY_REDIS_MASTER_STS:-statefulset/dragonfly-redis-master}"
DRAGONFLY_REDIS_REPLICAS_STS="${DRAGONFLY_REDIS_REPLICAS_STS:-statefulset/dragonfly-redis-replicas}"

if [[ -n "${KUBECONFIG:-}" && ! -f "$KUBECONFIG" ]]; then
  echo "clear-cache-dragonfly: kubeconfig not found: $KUBECONFIG" >&2
  exit 1
fi

failed=0
fail() { echo "clear-cache-dragonfly: $*" >&2; failed=1; }

# Stage 1. Wipe the dfdaemon stores. Reports what it freed and fails when nothing
# matched: a silent skip here is exactly how the seed store stayed warm across
# every arm. storage.dir is read from each pod's own dfdaemon.yaml, because the
# client keeps it at /var/run/dragonfly/data and the seed at /var/lib/dragonfly.
wipe_store_on_pod() {
  local pod="$1" component="$2"
  local -a kcmd
  echo ">>> Dragonfly cache dirs [$DRAGONFLY_CACHE_DIRS + storage.dir]: $pod ($component)" >&2
  kcmd=(riid_kc -n "$DFS" exec)
  [[ -n "${DRAGONFLY_CONTAINER:-}" ]] && kcmd+=(-c "$DRAGONFLY_CONTAINER")
  # POSIX sh (/bin/sh в образе клиента); без массивов/read -a Bash.
  kcmd+=("$pod" -- env DFS_CLEAR_DIRS="$DRAGONFLY_CACHE_DIRS" sh -ec '
    cfg=$(sed -n "/^storage:/,/^[a-z]/p" /etc/dragonfly/dfdaemon.yaml 2>/dev/null \
          | sed -n "s/^[[:space:]]*dir:[[:space:]]*//p" | tr -d "\"" | head -1)
    _IFS="$IFS"
    IFS=" "
    set -f
    hit=0
    for d in ${DFS_CLEAR_DIRS:-} ${cfg:-}; do
      [ -z "$d" ] && continue
      [ -d "$d" ] || continue
      before=$(du -sk "$d" 2>/dev/null | cut -f1)
      find "$d" -mindepth 1 -exec rm -rf {} + 2>/dev/null || true
      after=$(du -sk "$d" 2>/dev/null | cut -f1)
      echo "    cleared $d: ${before:-0}K -> ${after:-0}K"
      hit=$((hit + 1))
    done
    set +f
    IFS="$_IFS"
    if [ "$hit" -eq 0 ]; then
      echo "    no cache directory found (checked: ${DFS_CLEAR_DIRS} ${cfg:-<no storage.dir>})" >&2
      exit 1
    fi
  ')
  "${kcmd[@]}"
}

wipe_dfdaemon_stores() {
  local wiped_ok=0 component pod phase n total=0
  local -a dfpods
  for component in client seed-client; do
    mapfile -t dfpods < <(riid_kc -n "$DFS" get pods -l "app=dragonfly,component=$component" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
    for pod in "${dfpods[@]}"; do
      [[ -z "$pod" ]] && continue
      phase=$(riid_kc -n "$DFS" get pod "$pod" -o jsonpath='{.status.phase}')
      if [[ "$phase" != Running ]]; then
        echo ">>> skip Dragonfly $pod (phase=$phase)" >&2
        continue
      fi
      wipe_store_on_pod "$pod" "$component" || { fail "FAILED Dragonfly pod=$pod"; wiped_ok=1; }
    done
  done

  # Only meaningful when nothing failed above: an empty namespace and a namespace
  # whose every pod failed look the same from here.
  ((wiped_ok == 0)) || return 0
  for component in client seed-client; do
    n=$(riid_kc -n "$DFS" get pods -l "app=dragonfly,component=$component" --no-headers 2>/dev/null | wc -l | tr -d " ")
    total=$((total + n))
  done
  ((total > 0)) || fail "namespace $DFS exists but no app=dragonfly client/seed-client pods"
}

# Stage 2. The store was deleted underneath running dfdaemons, so their in-memory
# view of it is now wrong. Roll the data plane so client and seed come back on an
# empty store instead of carrying stale task state into the next arm.
restart_data_plane() {
  local res
  echo ">>> Dragonfly data-plane restart: $DRAGONFLY_CLIENT_DS, $DRAGONFLY_SEED_STS" >&2
  for res in "$DRAGONFLY_CLIENT_DS" "$DRAGONFLY_SEED_STS"; do
    riid_kc -n "$DFS" get "$res" >/dev/null 2>&1 || continue
    riid_kc -n "$DFS" rollout restart "$res" || { fail "FAILED rollout restart $res"; continue; }
    riid_kc -n "$DFS" rollout status "$res" --timeout="$DRAGONFLY_ROLLOUT_TIMEOUT" \
      || fail "FAILED rollout status $res"
  done
}

# Stage 3. With content gone the scheduler must forget its index over it, or every
# client goes back to source - which measured worse than a genuinely cold tier.
reset_control_plane() {
  local res
  echo ">>> Dragonfly control-plane reset: restart $DRAGONFLY_MANAGER_RESOURCE, $DRAGONFLY_SCHEDULER_RESOURCE" >&2
  for res in "$DRAGONFLY_MANAGER_RESOURCE" "$DRAGONFLY_SCHEDULER_RESOURCE"; do
    riid_kc -n "$DFS" get "$res" >/dev/null 2>&1 || { fail "REQUIRED resource not found: $res"; continue; }
    riid_kc -n "$DFS" rollout restart "$res" || { fail "FAILED rollout restart $res"; continue; }
    riid_kc -n "$DFS" rollout status "$res" --timeout="$DRAGONFLY_ROLLOUT_TIMEOUT" \
      || fail "FAILED rollout status $res"
  done
}

# Redis outlives a restart because its state is on a PVC, so the peer index is
# recreated by deleting the claims with the statefulsets scaled to zero.
redis_scale() {
  local sts="$1" n="$2"
  riid_kc -n "$DFS" scale "$sts" --replicas="$n" || { fail "FAILED scale $sts to $n"; return 1; }
}

# Collects the claims the redis pods actually mount, rather than guessing names.
redis_pvcs_of_running_pods() {
  local rpod pvc
  local -a redis_pods pod_pvcs
  mapfile -t redis_pods < <(riid_kc -n "$DFS" get pods -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | awk '/^dragonfly-redis-(master|replicas)-/')
  ((${#redis_pods[@]} > 0)) || return 1
  for rpod in "${redis_pods[@]}"; do
    mapfile -t pod_pvcs < <(riid_kc -n "$DFS" get pod "$rpod" -o jsonpath='{range .spec.volumes[*]}{.persistentVolumeClaim.claimName}{"\n"}{end}')
    for pvc in "${pod_pvcs[@]}"; do
      [[ -n "$pvc" ]] && printf '%s\n' "$pvc"
    done
  done | sort -u
}

# Stage 4. Early returns instead of nesting: every precondition failure below
# leaves the statefulsets untouched, which is the only safe state to stop in.
recreate_redis_state() {
  local master_n replicas_n pvc sts
  local -a redis_pvcs
  echo ">>> Dragonfly redis state recreation: $DRAGONFLY_REDIS_MASTER_STS, $DRAGONFLY_REDIS_REPLICAS_STS" >&2
  for sts in "$DRAGONFLY_REDIS_MASTER_STS" "$DRAGONFLY_REDIS_REPLICAS_STS"; do
    riid_kc -n "$DFS" get "$sts" >/dev/null 2>&1 || { fail "REQUIRED resource not found: $sts"; return 0; }
  done

  master_n="$(riid_kc -n "$DFS" get "$DRAGONFLY_REDIS_MASTER_STS" -o jsonpath='{.spec.replicas}')"
  replicas_n="$(riid_kc -n "$DFS" get "$DRAGONFLY_REDIS_REPLICAS_STS" -o jsonpath='{.spec.replicas}')"
  [[ "$master_n" =~ ^[0-9]+$ ]] || master_n=1
  [[ "$replicas_n" =~ ^[0-9]+$ ]] || replicas_n=1

  mapfile -t redis_pvcs < <(redis_pvcs_of_running_pods)
  if ((${#redis_pvcs[@]} == 0)); then
    fail "REQUIRED redis PVCs not found from redis pods (pattern: dragonfly-redis-(master|replicas)-*)"
    return 0
  fi

  redis_scale "$DRAGONFLY_REDIS_MASTER_STS" 0 || return 0
  redis_scale "$DRAGONFLY_REDIS_REPLICAS_STS" 0 || return 0

  for pvc in "${redis_pvcs[@]}"; do
    riid_kc -n "$DFS" delete pvc "$pvc" || fail "FAILED delete redis pvc=$pvc"
  done

  redis_scale "$DRAGONFLY_REDIS_MASTER_STS" "$master_n" || true
  redis_scale "$DRAGONFLY_REDIS_REPLICAS_STS" "$replicas_n" || true
  for sts in "$DRAGONFLY_REDIS_MASTER_STS" "$DRAGONFLY_REDIS_REPLICAS_STS"; do
    riid_kc -n "$DFS" rollout status "$sts" --timeout="$DRAGONFLY_ROLLOUT_TIMEOUT" \
      || fail "FAILED rollout status $sts"
  done
}

if ! riid_kc get namespace "$DFS" >/dev/null 2>&1; then
  fail "REQUIRED namespace not found: $DFS"
  exit "$failed"
fi

wipe_dfdaemon_stores
[[ "$DRAGONFLY_RESTART_DATA_PLANE" == "1" ]] && restart_data_plane
[[ "$DRAGONFLY_RESET_CONTROL_PLANE" == "1" ]] && reset_control_plane
# Scaling redis down on a stand that already failed a stage would deepen the
# damage, so this last stage runs only when everything above succeeded.
if [[ "$DRAGONFLY_RECREATE_REDIS_STATE" == "1" ]] && ((failed == 0)); then
  recreate_redis_state
fi

exit "$failed"
