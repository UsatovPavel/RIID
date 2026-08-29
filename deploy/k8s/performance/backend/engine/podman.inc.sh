#!/usr/bin/env bash
# Драйвер podman. Единственный движок, который живёт внутри бенч-пода: podman
# ставится в образ RIID (src/riid/Dockerfile.k8s), сокета на ноде ему не нужно.
#
# Env:
#   PODMAN_TLS_VERIFY               — принудительно true|false; по умолчанию
#                                     false для локального HTTP-реестра, иначе true
#   PODMAN_BASELINE_REGISTRIES_CONF — registries.conf без зеркала для baseline-арма;
#                                     пусто = файл из образа (поведение до dfinit)

_podman_tls_verify() {
  local v="${PODMAN_TLS_VERIFY:-${REGISTRY_TLS_VERIFY:-}}"
  if [[ -z "$v" ]]; then
    if riid_registry_is_plain_http; then v=false; else v=true; fi
  fi
  printf '%s\n' "$v"
}

engine_preflight() {
  riid_engine_exec "$1" sh -ec 'command -v podman >/dev/null'
}

# Без хоста ссылка остаётся короткой: podman дотягивает её через
# unqualified-search-registries, поэтому это рабочий кейс, а не ошибка.
engine_ref() {
  local repo="$1" tag="$2" host
  host="$(riid_registry_pull_host)"
  if [[ -n "$host" ]]; then
    printf '%s/%s:%s\n' "${host%/}" "$repo" "$tag"
  else
    printf '%s:%s\n' "$repo" "$tag"
  fi
}

engine_pull() {
  local pod="$1" ref="$2"
  if [[ -n "${PODMAN_BASELINE_REGISTRIES_CONF:-}" ]]; then
    # Baseline рядом с включённым dfinit: в поде смонтирован hostPath
    # /etc/containers с зеркалом, поэтому чистый arm читает отдельный файл.
    riid_engine_exec "$pod" env "CONTAINERS_REGISTRIES_CONF=${PODMAN_BASELINE_REGISTRIES_CONF}" \
      podman pull --tls-verify="$(_podman_tls_verify)" "$ref" >/dev/null
  else
    riid_engine_exec "$pod" podman pull --tls-verify="$(_podman_tls_verify)" "$ref" >/dev/null
  fi
}

# dfinit пишет registries.conf на хосте; под обязан монтировать его hostPath'ом,
# иначе podman возьмёт файл из образа и зеркало не применится.
engine_pull_mirrored() {
  local pod="$1" ref="$2"
  riid_engine_exec "$pod" podman pull --tls-verify="$(_podman_tls_verify)" "$ref" >/dev/null
}

# Без этой проверки dfinit-арм молча вырождается в обычный pull и меряет
# накладные расходы вместо P2P — ровно так уже терялся один прогон.
engine_mirror_check() {
  local pod="$1" conf="${PODMAN_REGISTRIES_CONF:-/etc/containers/registries.conf}"
  if ! riid_engine_exec "$pod" grep -qF "$RIID_DFINIT_PROXY_LOCATION" "$conf"; then
    echo "dfinit mirror not found: $conf in pod=$pod has no '$RIID_DFINIT_PROXY_LOCATION'" >&2
    echo "  проверьте hostPath /etc/containers в бенч-поде и client.dfinit в values" >&2
    return 1
  fi
}

# --volumes добавлен относительно старого backend/podman.sh: pull анонимных
# volume не создаёт, на замер не влияет, зато совпадает с
# scenario/clear/clear-cache-all-riid-pods.sh — одна семантика "чисто".
engine_clear_cache() {
  riid_engine_exec "$1" podman system prune -af --volumes >/dev/null
}
