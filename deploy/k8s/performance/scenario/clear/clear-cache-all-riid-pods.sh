#!/usr/bin/env bash
# Кластер: на каждом Running-поде RIID и подах Dragonfly client/seed-client:
#
# RIID (namespace по умолчанию riid-system):
#   • Podman: podman system prune -af --volumes. Podman теперь демон НОДЫ
#     (CONTAINER_HOST, см. src/engines/podman-node.yaml), поэтому prune чистит
#     стор ноды — тот самый, куда пишут и baseline-арм, и импорт RIID.
#   • RIID OCI-темп-кэш: каталоги riid-cache-tmp-* / riid-prefix-* и сироты
#     layer-*.bin. Лежат в RIID_WORK_DIR (app.tempDirectory), а это hostPath на
#     ноде — рестарт пода их больше НЕ чистит, только этот скрипт.
#
# Dragonfly (namespace по умолчанию dragonfly-system, см. DRAGONFLY_NAMESPACE):
#   • Поды app=dragonfly, component=client | seed-client (Helm OSS): очистить содержимое
#     каталогов из DRAGONFLY_CACHE_DIRS (по умолчанию логический кэш dfdaemon и P2P output,
#     см. scripts/values.yaml extraVolumeMounts /var/run/dragonfly/output).
#   • Namespace и обязательные control-plane ресурсы считаются обязательными:
#     при отсутствии скрипт падает.
#
# Ноды без соответствующих подов здесь не трогаются.
# По умолчанию в конце выполняется reset control-plane Dragonfly (manager/scheduler + Redis FLUSHALL).
# Не вызывайте во время активных pull.
#
# Env:
#   RIID_NAMESPACE        — default: riid-system
#   RIID_CONTAINER        — default: riid
#   RIID_LABEL_SELECTOR   — default: app.kubernetes.io/name=riid
#   RIID_WORK_DIR         — default: /var/lib/riid/work (== app.tempDirectory
#                           в configmap.yaml и hostPath riid-work-host)
#
#   DRAGONFLY_NAMESPACE           — default: dragonfly-system
#   DRAGONFLY_CACHE_DIRS           — пробел‑разделённый список каталогов (default: см. ниже)
#   DRAGONFLY_CONTAINER            — если пусто, kubectl exec без -c (один контейнер в поде).
#                                    Если в поде несколько контейнеров — задать имя явно (часто client/dfdaemon).
#   DRAGONFLY_RESET_CONTROL_PLANE  — 1/0, restart scheduler/manager (default: 1)
#   DRAGONFLY_RECREATE_REDIS_STATE — 1/0, пересоздать state Redis (scale-down sts, delete PVC, scale-up) (default: 1)
#   DRAGONFLY_MANAGER_RESOURCE     — default: deployment/dragonfly-manager
#   DRAGONFLY_SCHEDULER_RESOURCE   — default: statefulset/dragonfly-scheduler
#   DRAGONFLY_ROLLOUT_TIMEOUT      — default: 5m
#   DRAGONFLY_REDIS_MASTER_STS     — default: statefulset/dragonfly-redis-master
#   DRAGONFLY_REDIS_REPLICAS_STS   — default: statefulset/dragonfly-redis-replicas
set -euo pipefail

NS="${RIID_NAMESPACE:-riid-system}"
CONTAINER="${RIID_CONTAINER:-riid}"
LABEL="${RIID_LABEL_SELECTOR:-app.kubernetes.io/name=riid}"
WORK_DIR="${RIID_WORK_DIR:-/var/lib/riid/work}"

DFS="${DRAGONFLY_NAMESPACE:-dragonfly-system}"
# По умолчанию: дерево dfget/dfdaemon (часто /var/cache/dragonfly/dfdaemon под этим корнем)
# и hostPath‑вывод RIID (/var/run/dragonfly/output в values.yaml extraVolumeMounts).
DRAGONFLY_CACHE_DIRS="${DRAGONFLY_CACHE_DIRS:-/var/cache/dragonfly /var/run/dragonfly/output}"
DRAGONFLY_RESET_CONTROL_PLANE="${DRAGONFLY_RESET_CONTROL_PLANE:-1}"
DRAGONFLY_RECREATE_REDIS_STATE="${DRAGONFLY_RECREATE_REDIS_STATE:-1}"
DRAGONFLY_MANAGER_RESOURCE="${DRAGONFLY_MANAGER_RESOURCE:-deployment/dragonfly-manager}"
DRAGONFLY_SCHEDULER_RESOURCE="${DRAGONFLY_SCHEDULER_RESOURCE:-statefulset/dragonfly-scheduler}"
DRAGONFLY_ROLLOUT_TIMEOUT="${DRAGONFLY_ROLLOUT_TIMEOUT:-5m}"
DRAGONFLY_REDIS_MASTER_STS="${DRAGONFLY_REDIS_MASTER_STS:-statefulset/dragonfly-redis-master}"
DRAGONFLY_REDIS_REPLICAS_STS="${DRAGONFLY_REDIS_REPLICAS_STS:-statefulset/dragonfly-redis-replicas}"

if [[ -n "${KUBECONFIG:-}" && ! -f "$KUBECONFIG" ]]; then
  echo "clear-cache-all-riid-pods: kubeconfig not found: $KUBECONFIG" >&2
  exit 1
fi

failed=0

mapfile -t pods < <(kubectl -n "$NS" get pods -l "$LABEL" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')

if ((${#pods[@]} == 0)); then
  echo "clear-cache-all-riid-pods: no pods with label $LABEL in $NS" >&2
  exit 1
fi

for pod in "${pods[@]}"; do
  [[ -z "$pod" ]] && continue
  phase=$(kubectl -n "$NS" get pod "$pod" -o jsonpath='{.status.phase}')
  if [[ "$phase" != Running ]]; then
    echo ">>> skip RIID $pod (phase=$phase)" >&2
    continue
  fi
  echo ">>> RIID podman prune (node store) + RIID work cache [$WORK_DIR]: $pod" >&2
  if ! kubectl -n "$NS" exec -c "$CONTAINER" "$pod" -- env RIID_WORK_DIR="$WORK_DIR" sh -ec '
      podman system prune -af --volumes
      for d in "$RIID_WORK_DIR" /tmp; do
        [ -d "$d" ] || continue
        find "$d" -maxdepth 1 -type d -name '"'"'riid-cache-tmp-*'"'"' -exec rm -rf {} +
        find "$d" -maxdepth 1 -type d -name '"'"'riid-prefix-*'"'"' -exec rm -rf {} +
        find "$d" -maxdepth 1 -type f -name '"'"'layer-*.bin'"'"' -delete
      done
    '; then
    echo "clear-cache-all-riid-pods: FAILED RIID pod=$pod" >&2
    failed=1
  fi
done

if kubectl get namespace "$DFS" >/dev/null 2>&1; then
  df_failed_this_ns=0
  for component in client seed-client; do
    mapfile -t dfpods < <(kubectl -n "$DFS" get pods -l "app=dragonfly,component=$component" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
    for pod in "${dfpods[@]}"; do
      [[ -z "$pod" ]] && continue
      phase=$(kubectl -n "$DFS" get pod "$pod" -o jsonpath='{.status.phase}')
      if [[ "$phase" != Running ]]; then
        echo ">>> skip Dragonfly $pod (phase=$phase)" >&2
        continue
      fi
      echo ">>> Dragonfly cache dirs [$DRAGONFLY_CACHE_DIRS]: $pod ($component)" >&2
      kcmd=(kubectl -n "$DFS" exec)
      [[ -n "${DRAGONFLY_CONTAINER:-}" ]] && kcmd+=(-c "$DRAGONFLY_CONTAINER")
      # POSIX sh (/bin/sh в образе клиента); без массивов/read -a Bash.
      kcmd+=("$pod" -- env DFS_CLEAR_DIRS="$DRAGONFLY_CACHE_DIRS" sh -ec '
        _IFS="$IFS"
        IFS=" "
        set -f
        for d in ${DFS_CLEAR_DIRS:-}; do
          [ -z "$d" ] && continue
          [ -e "$d" ] || continue
          [ ! -d "$d" ] && continue
          find "$d" -mindepth 1 -exec rm -rf {} + 2>/dev/null || true
        done
        set +f
        IFS="$_IFS"
      ')
      if ! "${kcmd[@]}"; then
        echo "clear-cache-all-riid-pods: FAILED Dragonfly pod=$pod" >&2
        df_failed_this_ns=1
        failed=1
      fi
    done
  done
  if ((df_failed_this_ns == 0)); then
    total_df=0
    for component in client seed-client; do
      n=$(kubectl -n "$DFS" get pods -l "app=dragonfly,component=$component" --no-headers 2>/dev/null | wc -l | tr -d " ")
      total_df=$((total_df + n))
    done
    if ((total_df == 0)); then
      echo "clear-cache-all-riid-pods: namespace $DFS exists but no app=dragonfly client/seed-client pods" >&2
      failed=1
    fi
  fi

  if [[ "$DRAGONFLY_RESET_CONTROL_PLANE" == "1" ]]; then
    echo ">>> Dragonfly control-plane reset: restart $DRAGONFLY_MANAGER_RESOURCE, $DRAGONFLY_SCHEDULER_RESOURCE" >&2
    for res in "$DRAGONFLY_MANAGER_RESOURCE" "$DRAGONFLY_SCHEDULER_RESOURCE"; do
      if ! kubectl -n "$DFS" get "$res" >/dev/null 2>&1; then
        echo "clear-cache-all-riid-pods: REQUIRED resource not found: $res" >&2
        failed=1
        continue
      fi
      if ! kubectl -n "$DFS" rollout restart "$res"; then
        echo "clear-cache-all-riid-pods: FAILED rollout restart $res" >&2
        failed=1
        continue
      fi
      if ! kubectl -n "$DFS" rollout status "$res" --timeout="$DRAGONFLY_ROLLOUT_TIMEOUT"; then
        echo "clear-cache-all-riid-pods: FAILED rollout status $res" >&2
        failed=1
      fi
    done
  fi

  if [[ "$DRAGONFLY_RECREATE_REDIS_STATE" == "1" ]]; then
    echo ">>> Dragonfly redis state recreation: $DRAGONFLY_REDIS_MASTER_STS, $DRAGONFLY_REDIS_REPLICAS_STS" >&2
    if ! kubectl -n "$DFS" get "$DRAGONFLY_REDIS_MASTER_STS" >/dev/null 2>&1; then
      echo "clear-cache-all-riid-pods: REQUIRED resource not found: $DRAGONFLY_REDIS_MASTER_STS" >&2
      failed=1
    fi
    if ! kubectl -n "$DFS" get "$DRAGONFLY_REDIS_REPLICAS_STS" >/dev/null 2>&1; then
      echo "clear-cache-all-riid-pods: REQUIRED resource not found: $DRAGONFLY_REDIS_REPLICAS_STS" >&2
      failed=1
    fi

    if ((failed == 0)); then
      master_replicas="$(kubectl -n "$DFS" get "$DRAGONFLY_REDIS_MASTER_STS" -o jsonpath='{.spec.replicas}')"
      replicas_replicas="$(kubectl -n "$DFS" get "$DRAGONFLY_REDIS_REPLICAS_STS" -o jsonpath='{.spec.replicas}')"
      [[ "$master_replicas" =~ ^[0-9]+$ ]] || master_replicas=1
      [[ "$replicas_replicas" =~ ^[0-9]+$ ]] || replicas_replicas=1

      mapfile -t redis_pods < <(kubectl -n "$DFS" get pods -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' | awk '/^dragonfly-redis-(master|replicas)-/')
      if ((${#redis_pods[@]} == 0)); then
        echo "clear-cache-all-riid-pods: REQUIRED redis pods not found (pattern: dragonfly-redis-(master|replicas)-*)" >&2
        failed=1
      else
        declare -A redis_pvcs=()
        for rpod in "${redis_pods[@]}"; do
          mapfile -t pod_pvcs < <(kubectl -n "$DFS" get pod "$rpod" -o jsonpath='{range .spec.volumes[*]}{.persistentVolumeClaim.claimName}{"\n"}{end}')
          for pvc in "${pod_pvcs[@]}"; do
            [[ -n "$pvc" ]] || continue
            redis_pvcs["$pvc"]=1
          done
        done
        if ((${#redis_pvcs[@]} == 0)); then
          echo "clear-cache-all-riid-pods: REQUIRED redis PVCs not found from redis pods" >&2
          failed=1
        else
          if ! kubectl -n "$DFS" scale "$DRAGONFLY_REDIS_MASTER_STS" --replicas=0; then
            echo "clear-cache-all-riid-pods: FAILED scale down $DRAGONFLY_REDIS_MASTER_STS" >&2
            failed=1
          fi
          if ! kubectl -n "$DFS" scale "$DRAGONFLY_REDIS_REPLICAS_STS" --replicas=0; then
            echo "clear-cache-all-riid-pods: FAILED scale down $DRAGONFLY_REDIS_REPLICAS_STS" >&2
            failed=1
          fi

          if ((failed == 0)); then
            for pvc in "${!redis_pvcs[@]}"; do
              if ! kubectl -n "$DFS" delete pvc "$pvc"; then
                echo "clear-cache-all-riid-pods: FAILED delete redis pvc=$pvc" >&2
                failed=1
              fi
            done
          fi

          if ! kubectl -n "$DFS" scale "$DRAGONFLY_REDIS_MASTER_STS" --replicas="$master_replicas"; then
            echo "clear-cache-all-riid-pods: FAILED scale up $DRAGONFLY_REDIS_MASTER_STS to $master_replicas" >&2
            failed=1
          fi
          if ! kubectl -n "$DFS" scale "$DRAGONFLY_REDIS_REPLICAS_STS" --replicas="$replicas_replicas"; then
            echo "clear-cache-all-riid-pods: FAILED scale up $DRAGONFLY_REDIS_REPLICAS_STS to $replicas_replicas" >&2
            failed=1
          fi
          if ! kubectl -n "$DFS" rollout status "$DRAGONFLY_REDIS_MASTER_STS" --timeout="$DRAGONFLY_ROLLOUT_TIMEOUT"; then
            echo "clear-cache-all-riid-pods: FAILED rollout status $DRAGONFLY_REDIS_MASTER_STS" >&2
            failed=1
          fi
          if ! kubectl -n "$DFS" rollout status "$DRAGONFLY_REDIS_REPLICAS_STS" --timeout="$DRAGONFLY_ROLLOUT_TIMEOUT"; then
            echo "clear-cache-all-riid-pods: FAILED rollout status $DRAGONFLY_REDIS_REPLICAS_STS" >&2
            failed=1
          fi
        fi
      fi
    fi
  fi
else
  echo "clear-cache-all-riid-pods: REQUIRED namespace not found: $DFS" >&2
  failed=1
fi

exit "$failed"
