#!/usr/bin/env bash
# Драйвер Porto. Движок живёт на ноде, как containerd: бенч-поду нужен hostPath
# /run/portod.socket и бинарь portoctl в образе — тот же набор, что уже требуется
# арму RIID => Porto (PortoRuntimeAdapter).
#
# Тянет реестр сам portod через `portoctl docker-pull`, поэтому у стенда есть два
# требования, которых нет у podman и containerd:
#
#   1. В /etc/portod.conf должно стоять `container { docker_images_support: true }`
#      — без него docker-pull/docker-images/docker-rmi выключены целиком.
#   2. HTTP-реестр перечисляется там же в `docker_insecure_registry`: флага уровня
#      команды, как --tls-verify=false у podman или --plain-http у ctr, у Porto нет.
#
# И отдельно про имя хоста: HTTP-запросы делает portod в host netns, где нет
# резолвера кластера. Cлужебное имя вида *.svc.cluster.local оттуда не
# резолвится, поэтому REGISTRY_PULL_HOST для этого арма должен указывать на
# адрес, видимый с ноды (NodePort реестра или внешний реестр).
#
# Env:
#   PORTO_PLACE    — -P <place>, пусто = дефолтный /place
#   PORTO_PLATFORM — -T amd64|aarch64, пусто = дефолт portod
#   PORTO_SOCKET   — сокет portod для preflight (default /run/portod.socket)
#   DOCKER_TOKEN   — проброс токена реестра в docker-pull, как в документации Porto

PORTO_SOCKET="${PORTO_SOCKET:-/run/portod.socket}"
PORTO_PLACE="${PORTO_PLACE:-}"
PORTO_PLATFORM="${PORTO_PLATFORM:-}"

# Аргументы portoctl до подкоманды и общие флаги места, по одному в строке.
_porto_place_flags() {
  if [[ -n "$PORTO_PLACE" ]]; then
    printf '%s\n' -P "$PORTO_PLACE"
  fi
}

engine_preflight() {
  local pod="$1"
  local -a place
  mapfile -t place < <(_porto_place_flags)

  riid_engine_exec "$pod" env "PORTO_SOCK=$PORTO_SOCKET" sh -ec '
    command -v portoctl >/dev/null || { echo "portoctl not found in pod" >&2; exit 1; }
    [ -S "$PORTO_SOCK" ] || { echo "porto socket not mounted: $PORTO_SOCK" >&2; exit 1; }
  ' || return 1

  # docker_images_support выключен по умолчанию, и тогда docker-pull падает уже
  # на первом образе, посреди замера. Дешевле узнать об этом здесь.
  if ! riid_engine_exec "$pod" portoctl docker-images "${place[@]}" >/dev/null 2>&1; then
    echo "porto: docker images support is off in pod=$pod" >&2
    echo "  добавьте в /etc/portod.conf: container { docker_images_support: true } и перезапустите porto" >&2
    return 1
  fi
}

# Porto достраивать короткую ссылку не умеет: ни unqualified-search-registries,
# как у podman, ни чего-то похожего у него нет.
engine_ref() {
  local repo="$1" tag="$2" host
  host="$(riid_registry_pull_host)"
  if [[ -z "$host" ]]; then
    echo "porto: registry host is empty, set REGISTRY_PULL_HOST (portoctl docker-pull needs a fully qualified ref)" >&2
    return 2
  fi
  printf '%s/%s:%s\n' "${host%/}" "$repo" "$tag"
}

engine_pull() {
  local pod="$1" ref="$2"
  local -a place args
  mapfile -t place < <(_porto_place_flags)

  args=(portoctl docker-pull "${place[@]}")
  if [[ -n "$PORTO_PLATFORM" ]]; then
    args+=(-T "$PORTO_PLATFORM")
  fi

  # docker-pull печатает id образа — на замер не влияет, но в TSV не нужен.
  if [[ -n "${DOCKER_TOKEN:-}" ]]; then
    riid_engine_exec "$pod" env "DOCKER_TOKEN=$DOCKER_TOKEN" "${args[@]}" "$ref" >/dev/null
  else
    riid_engine_exec "$pod" "${args[@]}" "$ref" >/dev/null
  fi
}

# dfinit не умеет Porto: его ContainerRuntimeConfig покрывает containerd, docker,
# podman и cri-o, а конфига зеркала у Porto нет в принципе — реестр берётся из
# самой ссылки. Поэтому арм не деградирует в обычный pull, а честно падает:
# по матрице AGENT-99 у Porto есть только RIID => Porto и голый Porto.
_porto_no_dfinit() {
  echo "porto: dfinit не поддерживает этот движок (нет ни backend в dragonfly-client-init, ни конфига зеркала у Porto)" >&2
  echo "  по AGENT-99 у Porto только два арма: BACKEND=riid и BACKEND=bare" >&2
  return 2
}

engine_pull_mirrored() { _porto_no_dfinit; }
engine_mirror_check() { _porto_no_dfinit; }

# docker-images печатает заголовок "ID           NAME" и по строке на тег;
# удаляется именно тег, потому что docker-rmi принимает имя образа.
engine_clear_cache() {
  local pod="$1"
  riid_engine_exec "$pod" env "PORTO_PLACE=$PORTO_PLACE" sh -ec '
    if [ -n "$PORTO_PLACE" ]; then set -- -P "$PORTO_PLACE"; else set --; fi
    portoctl docker-images "$@" | awk "NR > 1 && NF >= 2 { print \$2 }" | while read -r img; do
      [ -n "$img" ] || continue
      portoctl docker-rmi "$@" "$img" >/dev/null
    done
  '
}
