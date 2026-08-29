#!/usr/bin/env bash
# Shared part of the engine drivers. Sourced from backend/bare.sh and
# backend/dfinit.sh; not runnable on its own.
#
# Driver contract — every engine/<name>.inc.sh must define:
#   engine_preflight <pod>            engine reachable in the pod (binary, socket)
#   engine_ref <repo> <tag>           prints the full reference to pull
#   engine_pull <pod> <ref>           baseline pull, no dfinit mirror
#   engine_pull_mirrored <pod> <ref>  pull through the mirror dfinit wrote
#   engine_mirror_check <pod>         the mirror really did apply
#   engine_clear_cache <pod>          wipe the local image store
#
# Engines take the mirror differently: podman reads a registries.conf file,
# containerd takes --hosts-dir as an argument. Hence two separate functions
# instead of one with a boolean flag.

# kubectl exec into the bench pod. Every engine command goes through it.
riid_engine_exec() {
  local pod="$1"
  shift
  kubectl -n "$NS" exec -c "$CONTAINER" "$pod" -- "$@"
}

# Reads a single value from config/.env literally.
# `set -a; source .env` cannot be used here: bash expands $, backticks and \
# inside the values, and this same file holds registry tokens — substitution
# would corrupt a value at best and run a command from it at worst.
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

# Registry host the baseline and dfinit arms pull from: an explicit
# REGISTRY_PULL_HOST, then the hint from resolve_smoke_repository.py, then the
# legacy REGISTRY_LOGIN_HOST in config/.env.
riid_registry_pull_host() {
  local host py cfg name
  host="${REGISTRY_PULL_HOST:-}"
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

# The in-cluster registry speaks plain HTTP. What gets checked is the host as
# configured, not the resolved one.
RIID_LOCAL_REGISTRY_HOST="${RIID_LOCAL_REGISTRY_HOST:-local-registry.registry-system.svc.cluster.local:5000}"

riid_registry_is_plain_http() {
  local host="${REGISTRY_PULL_HOST:-}"
  [[ "$host" == "$RIID_LOCAL_REGISTRY_HOST" ]]
}

# Address of the dfdaemon proxy that dfinit writes into the engine as a mirror.
# Chart default (client.dfinit.config.proxy.addr: http://127.0.0.1:4001).
RIID_DFINIT_PROXY_LOCATION="${RIID_DFINIT_PROXY_LOCATION:-127.0.0.1:4001}"
