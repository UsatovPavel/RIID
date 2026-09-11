#!/usr/bin/env bash
# Dragonfly's cache only: the dfdaemon stores plus the control-plane state that
# indexes them. Not the engine store (clear-cache-engines.sh) and not RIID's
# scratch (clear-cache-riid.sh) - each of the three is cleared by its own script.
#
# storage.dir differs per component: the client keeps it at /var/run/dragonfly/data,
# the seed at /var/lib/dragonfly. A single static list missed the seed entirely, so
# the dir is read from each pod's own dfdaemon.yaml at runtime and the pass fails
# when it matched nothing. Content and control-plane state are reset together: with
# content left behind the scheduler no longer knows about it and every client goes
# back to source, which measured *worse* than a genuinely cold tier.
#
# Env:
#   DRAGONFLY_NAMESPACE            - default: dragonfly-system
#   DRAGONFLY_CACHE_DIRS           - extra dirs; storage.dir is added per pod
#   DRAGONFLY_CONTAINER            - empty means riid_kc exec without -c
#   DRAGONFLY_RESET_CONTROL_PLANE  - 1/0, restart manager+scheduler (default 1)
#   DRAGONFLY_RESTART_DATA_PLANE   - 1/0, restart client+seed (default 1)
#   DRAGONFLY_RECREATE_REDIS_STATE - 1/0, recreate Redis PVCs (default 1)
#   DRAGONFLY_ROLLOUT_TIMEOUT      - default: 5m
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

if riid_kc get namespace "$DFS" >/dev/null 2>&1; then
  df_failed_this_ns=0
  for component in client seed-client; do
    mapfile -t dfpods < <(riid_kc -n "$DFS" get pods -l "app=dragonfly,component=$component" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
    for pod in "${dfpods[@]}"; do
      [[ -z "$pod" ]] && continue
      phase=$(riid_kc -n "$DFS" get pod "$pod" -o jsonpath='{.status.phase}')
      if [[ "$phase" != Running ]]; then
        echo ">>> skip Dragonfly $pod (phase=$phase)" >&2
        continue
      fi
      echo ">>> Dragonfly cache dirs [$DRAGONFLY_CACHE_DIRS + storage.dir]: $pod ($component)" >&2
      kcmd=(riid_kc -n "$DFS" exec)
      [[ -n "${DRAGONFLY_CONTAINER:-}" ]] && kcmd+=(-c "$DRAGONFLY_CONTAINER")
      # POSIX sh (/bin/sh в образе клиента); без массивов/read -a Bash.
      # Reports what it freed and fails when nothing matched: a silent skip here
      # is exactly how the seed store stayed warm across every arm.
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
      if ! "${kcmd[@]}"; then
        echo "clear-cache-dragonfly: FAILED Dragonfly pod=$pod" >&2
        df_failed_this_ns=1
        failed=1
      fi
    done
  done
  if ((df_failed_this_ns == 0)); then
    total_df=0
    for component in client seed-client; do
      n=$(riid_kc -n "$DFS" get pods -l "app=dragonfly,component=$component" --no-headers 2>/dev/null | wc -l | tr -d " ")
      total_df=$((total_df + n))
    done
    if ((total_df == 0)); then
      echo "clear-cache-dragonfly: namespace $DFS exists but no app=dragonfly client/seed-client pods" >&2
      failed=1
    fi
  fi

  # The store was deleted underneath running dfdaemons, so their in-memory view of
  # it is now wrong. Roll the data plane so client and seed come back on an empty
  # store instead of carrying stale task state into the next arm.
  if [[ "$DRAGONFLY_RESTART_DATA_PLANE" == "1" ]]; then
    echo ">>> Dragonfly data-plane restart: $DRAGONFLY_CLIENT_DS, $DRAGONFLY_SEED_STS" >&2
    for res in "$DRAGONFLY_CLIENT_DS" "$DRAGONFLY_SEED_STS"; do
      riid_kc -n "$DFS" get "$res" >/dev/null 2>&1 || continue
      if ! riid_kc -n "$DFS" rollout restart "$res"; then
        echo "clear-cache-dragonfly: FAILED rollout restart $res" >&2
        failed=1
        continue
      fi
      if ! riid_kc -n "$DFS" rollout status "$res" --timeout="$DRAGONFLY_ROLLOUT_TIMEOUT"; then
        echo "clear-cache-dragonfly: FAILED rollout status $res" >&2
        failed=1
      fi
    done
  fi

  if [[ "$DRAGONFLY_RESET_CONTROL_PLANE" == "1" ]]; then
    echo ">>> Dragonfly control-plane reset: restart $DRAGONFLY_MANAGER_RESOURCE, $DRAGONFLY_SCHEDULER_RESOURCE" >&2
    for res in "$DRAGONFLY_MANAGER_RESOURCE" "$DRAGONFLY_SCHEDULER_RESOURCE"; do
      if ! riid_kc -n "$DFS" get "$res" >/dev/null 2>&1; then
        echo "clear-cache-dragonfly: REQUIRED resource not found: $res" >&2
        failed=1
        continue
      fi
      if ! riid_kc -n "$DFS" rollout restart "$res"; then
        echo "clear-cache-dragonfly: FAILED rollout restart $res" >&2
        failed=1
        continue
      fi
      if ! riid_kc -n "$DFS" rollout status "$res" --timeout="$DRAGONFLY_ROLLOUT_TIMEOUT"; then
        echo "clear-cache-dragonfly: FAILED rollout status $res" >&2
        failed=1
      fi
    done
  fi

  if [[ "$DRAGONFLY_RECREATE_REDIS_STATE" == "1" ]]; then
    echo ">>> Dragonfly redis state recreation: $DRAGONFLY_REDIS_MASTER_STS, $DRAGONFLY_REDIS_REPLICAS_STS" >&2
    if ! riid_kc -n "$DFS" get "$DRAGONFLY_REDIS_MASTER_STS" >/dev/null 2>&1; then
      echo "clear-cache-dragonfly: REQUIRED resource not found: $DRAGONFLY_REDIS_MASTER_STS" >&2
      failed=1
    fi
    if ! riid_kc -n "$DFS" get "$DRAGONFLY_REDIS_REPLICAS_STS" >/dev/null 2>&1; then
      echo "clear-cache-dragonfly: REQUIRED resource not found: $DRAGONFLY_REDIS_REPLICAS_STS" >&2
      failed=1
    fi

    if ((failed == 0)); then
      master_replicas="$(riid_kc -n "$DFS" get "$DRAGONFLY_REDIS_MASTER_STS" -o jsonpath='{.spec.replicas}')"
      replicas_replicas="$(riid_kc -n "$DFS" get "$DRAGONFLY_REDIS_REPLICAS_STS" -o jsonpath='{.spec.replicas}')"
      [[ "$master_replicas" =~ ^[0-9]+$ ]] || master_replicas=1
      [[ "$replicas_replicas" =~ ^[0-9]+$ ]] || replicas_replicas=1

      mapfile -t redis_pods < <(riid_kc -n "$DFS" get pods -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | awk '/^dragonfly-redis-(master|replicas)-/')
      if ((${#redis_pods[@]} == 0)); then
        echo "clear-cache-dragonfly: REQUIRED redis pods not found (pattern: dragonfly-redis-(master|replicas)-*)" >&2
        failed=1
      else
        declare -A redis_pvcs=()
        for rpod in "${redis_pods[@]}"; do
          mapfile -t pod_pvcs < <(riid_kc -n "$DFS" get pod "$rpod" -o jsonpath='{range .spec.volumes[*]}{.persistentVolumeClaim.claimName}{"\n"}{end}')
          for pvc in "${pod_pvcs[@]}"; do
            [[ -n "$pvc" ]] || continue
            redis_pvcs["$pvc"]=1
          done
        done
        if ((${#redis_pvcs[@]} == 0)); then
          echo "clear-cache-dragonfly: REQUIRED redis PVCs not found from redis pods" >&2
          failed=1
        else
          if ! riid_kc -n "$DFS" scale "$DRAGONFLY_REDIS_MASTER_STS" --replicas=0; then
            echo "clear-cache-dragonfly: FAILED scale down $DRAGONFLY_REDIS_MASTER_STS" >&2
            failed=1
          fi
          if ! riid_kc -n "$DFS" scale "$DRAGONFLY_REDIS_REPLICAS_STS" --replicas=0; then
            echo "clear-cache-dragonfly: FAILED scale down $DRAGONFLY_REDIS_REPLICAS_STS" >&2
            failed=1
          fi

          if ((failed == 0)); then
            for pvc in "${!redis_pvcs[@]}"; do
              if ! riid_kc -n "$DFS" delete pvc "$pvc"; then
                echo "clear-cache-dragonfly: FAILED delete redis pvc=$pvc" >&2
                failed=1
              fi
            done
          fi

          if ! riid_kc -n "$DFS" scale "$DRAGONFLY_REDIS_MASTER_STS" --replicas="$master_replicas"; then
            echo "clear-cache-dragonfly: FAILED scale up $DRAGONFLY_REDIS_MASTER_STS to $master_replicas" >&2
            failed=1
          fi
          if ! riid_kc -n "$DFS" scale "$DRAGONFLY_REDIS_REPLICAS_STS" --replicas="$replicas_replicas"; then
            echo "clear-cache-dragonfly: FAILED scale up $DRAGONFLY_REDIS_REPLICAS_STS to $replicas_replicas" >&2
            failed=1
          fi
          if ! riid_kc -n "$DFS" rollout status "$DRAGONFLY_REDIS_MASTER_STS" --timeout="$DRAGONFLY_ROLLOUT_TIMEOUT"; then
            echo "clear-cache-dragonfly: FAILED rollout status $DRAGONFLY_REDIS_MASTER_STS" >&2
            failed=1
          fi
          if ! riid_kc -n "$DFS" rollout status "$DRAGONFLY_REDIS_REPLICAS_STS" --timeout="$DRAGONFLY_ROLLOUT_TIMEOUT"; then
            echo "clear-cache-dragonfly: FAILED rollout status $DRAGONFLY_REDIS_REPLICAS_STS" >&2
            failed=1
          fi
        fi
      fi
    fi
  fi
else
  echo "clear-cache-dragonfly: REQUIRED namespace not found: $DFS" >&2
  failed=1
fi

exit "$failed"
