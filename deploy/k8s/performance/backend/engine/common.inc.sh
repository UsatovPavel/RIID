#!/usr/bin/env bash
# Общая часть драйверов движков. Сорсится из backend/bare.sh и backend/dfinit.sh,
# самостоятельно не запускается.
#
# Контракт драйвера (каждый engine/<name>.inc.sh обязан определить):
#   engine_preflight <pod>            — движок доступен в поде (бинарь, сокет)
#   engine_ref <repo> <tag>           — печатает полную ссылку для pull
#   engine_pull <pod> <ref>           — baseline pull, без зеркала dfinit
#   engine_pull_mirrored <pod> <ref>  — pull через зеркало, которое написал dfinit
#   engine_mirror_check <pod>         — зеркало действительно применилось
#   engine_clear_cache <pod>          — вычистить локальное хранилище образов
#
# Зеркало включается у движков по-разному: podman читает файл registries.conf,
# containerd получает --hosts-dir аргументом. Поэтому в контракте две отдельные
# функции, а не одна с булевым флагом.

# kubectl exec в бенч-под. Все команды движка идут только через неё.
riid_engine_exec() {
  local pod="$1"
  shift
  kubectl -n "$NS" exec -c "$CONTAINER" "$pod" -- "$@"
}

# Читает одно значение из config/.env буквально.
# `set -a; source .env` здесь неприменим: bash раскрывает $, backtick и \ внутри
# значений, а в этом же файле лежат токены реестров — подстановка в лучшем случае
# испортит значение, в худшем выполнит команду из него.
riid_env_value() {
  local key="$1" file="$2" line value
  [[ -f "$file" ]] || return 1
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" == "$key="* ]] || continue
    value="${line#*=}"
    if [[ "$value" == \"*\" && ${#value} -ge 2 ]]; then
      value="${value:1:${#value}-2}"
    elif [[ "$value" == \'*\' && ${#value} -ge 2 ]]; then
      value="${value:1:${#value}-2}"
    fi
    printf '%s\n' "$value"
    return 0
  done <"$file"
  return 1
}

# Хост реестра, из которого тянут baseline/dfinit армы: явный REGISTRY_PULL_HOST
# (legacy-имя PODMAN_REGISTRY_PULL_HOST), затем подсказка из
# resolve_smoke_repository.py, затем legacy REGISTRY_LOGIN_HOST из config/.env.
riid_registry_pull_host() {
  local host py cfg name
  host="${REGISTRY_PULL_HOST:-${PODMAN_REGISTRY_PULL_HOST:-}}"
  if [[ -n "$host" ]]; then
    printf '%s\n' "$host"
    return 0
  fi

  py="${RIID_K8S_ROOT}/providers/registry/image/resolve_smoke_repository.py"
  if [[ -f "$py" ]]; then
    host="$(python3 "$py" --k8s-root "$RIID_K8S_ROOT" --pull-host-hint 2>/dev/null || true)"
  fi

  if [[ -z "$host" ]]; then
    cfg="${PERF_REGISTRY_ENV_FILE:-$RIID_K8S_ROOT/config/.env}"
    name="${REGISTRY_SELECTEL_NAME:-$(riid_env_value REGISTRY_SELECTEL_NAME "$cfg" 2>/dev/null || true)}"
    if [[ -n "$name" ]]; then
      host="${REGISTRY_LOGIN_HOST:-$(riid_env_value REGISTRY_LOGIN_HOST "$cfg" 2>/dev/null || true)}"
      host="${host:-cr.selcloud.ru}"
    fi
  fi

  printf '%s\n' "$host"
}

# Локальный in-cluster registry ходит по plain HTTP. Проверяется именно
# заданный хост, а не разрезолвленный — так же, как это делал backend/podman.sh.
RIID_LOCAL_REGISTRY_HOST="${RIID_LOCAL_REGISTRY_HOST:-local-registry.registry-system.svc.cluster.local:5000}"

riid_registry_is_plain_http() {
  local host="${REGISTRY_PULL_HOST:-${PODMAN_REGISTRY_PULL_HOST:-}}"
  [[ "$host" == "$RIID_LOCAL_REGISTRY_HOST" ]]
}

# Адрес прокси dfdaemon, который dfinit прописывает движку как зеркало.
# Дефолт чарта dragonfly (client.dfinit.config.proxy.addr: http://127.0.0.1:4001).
RIID_DFINIT_PROXY_LOCATION="${RIID_DFINIT_PROXY_LOCATION:-127.0.0.1:4001}"
