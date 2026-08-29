#!/usr/bin/env bash
# Драйвер containerd. Движок живёт на ноде, а не в поде: бенч-поду нужен
# hostPath /run/containerd/containerd.sock и бинарь ctr в образе — тот же набор,
# что уже требуется арму RIID => Containerd (ContainerdRuntimeAdapter).
#
# В дефолтном режиме `ctr images pull` идёт через transfer service, то есть
# HTTP-запросы делает демон containerd в host netns. Оттуда же виден
# 127.0.0.1:4001, на котором слушает прокси dfdaemon. --hosts-dir и --plain-http
# в этом режиме поддерживаются; --skip-verify/--tlscacert требуют --local.
#
# Env:
#   CONTAINERD_NAMESPACE   — namespace для бенча (default riid-bench, не k8s.io)
#   CONTAINERD_ADDRESS     — сокет, пусто = дефолт ctr
#   CONTAINERD_SNAPSHOTTER — пусто = дефолт ctr
#   CONTAINERD_HOSTS_DIR   — куда dfinit положил certs.d (default /etc/containerd/certs.d)

CTR_NAMESPACE="${CONTAINERD_NAMESPACE:-riid-bench}"
CTR_ADDRESS="${CONTAINERD_ADDRESS:-}"
CTR_SNAPSHOTTER="${CONTAINERD_SNAPSHOTTER:-}"
CTR_HOSTS_DIR="${CONTAINERD_HOSTS_DIR:-/etc/containerd/certs.d}"

if ! [[ "$CTR_NAMESPACE" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  echo "CONTAINERD_NAMESPACE has unexpected characters: $CTR_NAMESPACE" >&2
  exit 2
fi

# Аргументы ctr до подкоманды, по одному в строке (читается через mapfile).
_ctr_base() {
  printf '%s\n' ctr
  if [[ -n "$CTR_ADDRESS" ]]; then
    printf '%s\n' -a "$CTR_ADDRESS"
  fi
  printf '%s\n' -n "$CTR_NAMESPACE"
}

# Флаги самого pull, общие для baseline и зеркала.
_ctr_pull_flags() {
  if riid_registry_is_plain_http; then
    printf '%s\n' --plain-http
  fi
  if [[ -n "$CTR_SNAPSHOTTER" ]]; then
    printf '%s\n' --snapshotter "$CTR_SNAPSHOTTER"
  fi
}

engine_preflight() {
  local pod="$1" addr="${CTR_ADDRESS:-/run/containerd/containerd.sock}"
  riid_engine_exec "$pod" env "CTR_SOCK=$addr" sh -ec '
    command -v ctr >/dev/null || { echo "ctr not found in pod" >&2; exit 1; }
    [ -S "$CTR_SOCK" ] || { echo "containerd socket not mounted: $CTR_SOCK" >&2; exit 1; }
  '
}

# ctr требует полностью квалифицированную ссылку: unqualified-search-registries,
# как у podman, у него нет, короткий repo:tag он не достроит.
engine_ref() {
  local repo="$1" tag="$2" host
  host="$(riid_registry_pull_host)"
  if [[ -z "$host" ]]; then
    echo "containerd: registry host is empty, set REGISTRY_PULL_HOST (ctr needs a fully qualified ref)" >&2
    return 2
  fi
  printf '%s/%s:%s\n' "${host%/}" "$repo" "$tag"
}

engine_pull() {
  local pod="$1" ref="$2"
  local -a base flags
  mapfile -t base < <(_ctr_base)
  mapfile -t flags < <(_ctr_pull_flags)
  riid_engine_exec "$pod" "${base[@]}" images pull "${flags[@]}" "$ref" >/dev/null
}

# Зеркало включается аргументом, а не файлом: без --hosts-dir клиентский ctr
# certs.d не читает вовсе, поэтому baseline и dfinit-арм спокойно сосуществуют
# на одной ноде и порядок прогона не важен.
engine_pull_mirrored() {
  local pod="$1" ref="$2"
  local -a base flags
  mapfile -t base < <(_ctr_base)
  mapfile -t flags < <(_ctr_pull_flags)
  riid_engine_exec "$pod" "${base[@]}" images pull "${flags[@]}" --hosts-dir "$CTR_HOSTS_DIR" "$ref" >/dev/null
}

engine_mirror_check() {
  local pod="$1"
  if ! riid_engine_exec "$pod" env "DIR=$CTR_HOSTS_DIR" "LOC=$RIID_DFINIT_PROXY_LOCATION" sh -ec '
        [ -d "$DIR" ] || { echo "hosts dir missing: $DIR" >&2; exit 1; }
        grep -rqF "$LOC" "$DIR"
      '; then
    echo "dfinit mirror not found: $CTR_HOSTS_DIR in pod=$pod has no '$RIID_DFINIT_PROXY_LOCATION'" >&2
    echo "  проверьте hostPath /etc/containerd в бенч-поде и client.dfinit в values" >&2
    return 1
  fi
}

# content store общий на все namespace'ы, поэтому мало снести образы — надо
# отпустить и неотрефересенный контент, иначе следующий "холодный" pull тёплый.
engine_clear_cache() {
  local pod="$1"
  riid_engine_exec "$pod" env "CTR_NS=$CTR_NAMESPACE" "CTR_ADDR=$CTR_ADDRESS" sh -ec '
    set -- ctr
    if [ -n "$CTR_ADDR" ]; then set -- "$@" -a "$CTR_ADDR"; fi
    set -- "$@" -n "$CTR_NS"
    "$@" images ls -q | while read -r img; do
      [ -n "$img" ] || continue
      "$@" images rm --sync "$img" >/dev/null
    done
    "$@" content prune references >/dev/null
  '
}
