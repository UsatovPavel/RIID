#!/usr/bin/env bash
# Кластер: на каждом Running-поде RIID и подах Dragonfly client/seed-client:
#
# RIID (namespace по умолчанию riid-system):
#   • Podman: podman system prune -af --volumes (полный prune, включая анонимные volumes).
#   • RIID OCI-темп-кэш: каталоги riid-cache-tmp-* (TempFileCacheAdapter) и сироты layer-*.bin во /tmp.
#
# Dragonfly (namespace по умолчанию dragonfly-system, см. DRAGONFLY_NAMESPACE):
#   • Поды app=dragonfly, component=client | seed-client (Helm OSS): очистить содержимое
#     каталогов из DRAGONFLY_CACHE_DIRS (по умолчанию логический кэш dfdaemon и P2P output,
#     см. scripts/values.yaml extraVolumeMounts /var/run/dragonfly/output).
#   • Если namespace Dragonfly отсутствует — фаза пропускается без ошибки.
#
# Ноды без соответствующих подов здесь не трогаются. Демоны не перезапускаются —
# не вызывайте во время активных pull.
#
# Env:
#   RIID_NAMESPACE        — default: riid-system
#   RIID_CONTAINER        — default: riid
#   RIID_LABEL_SELECTOR   — default: app.kubernetes.io/name=riid
#
#   DRAGONFLY_NAMESPACE           — default: dragonfly-system
#   DRAGONFLY_CACHE_DIRS           — пробел‑разделённый список каталогов (default: см. ниже)
#   DRAGONFLY_CONTAINER            — если пусто, kubectl exec без -c (один контейнер в поде).
#                                    Если в поде несколько контейнеров — задать имя явно (часто client/dfdaemon).
set -euo pipefail

NS="${RIID_NAMESPACE:-riid-system}"
CONTAINER="${RIID_CONTAINER:-riid}"
LABEL="${RIID_LABEL_SELECTOR:-app.kubernetes.io/name=riid}"

DFS="${DRAGONFLY_NAMESPACE:-dragonfly-system}"
# По умолчанию: дерево dfget/dfdaemon (часто /var/cache/dragonfly/dfdaemon под этим корнем)
# и hostPath‑вывод RIID (/var/run/dragonfly/output в values.yaml extraVolumeMounts).
DRAGONFLY_CACHE_DIRS="${DRAGONFLY_CACHE_DIRS:-/var/cache/dragonfly /var/run/dragonfly/output}"

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
  echo ">>> RIID podman prune + RIID tmp cache: $pod" >&2
  if ! kubectl -n "$NS" exec -c "$CONTAINER" "$pod" -- sh -ec '
      podman system prune -af --volumes
      find /tmp -maxdepth 1 -type d -name '"'"'riid-cache-tmp-*'"'"' -exec rm -rf {} +
      find /tmp -maxdepth 1 -type f -name '"'"'layer-*.bin'"'"' -delete
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
      echo "clear-cache-all-riid-pods: namespace $DFS exists but no app=dragonfly client/seed-client pods (skip DF cache)" >&2
    fi
  fi
else
  echo ">>> skip Dragonfly: namespace '$DFS' not found" >&2
fi

exit "$failed"
