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
# Optional, called by bare.sh only when the driver defines it:
#   engine_no_mirror_check <pod>      the mirror is really ABSENT
# It exists because engines that run on the node share one config with the
# dfinit arm: for podman, dfinit edits the node's /etc/containers/registries.conf
# and the baseline can no longer be pointed at a private copy of that file
# through a client-side env var. Without the check the baseline silently becomes
# a second dfinit arm. containerd and Porto do not define it yet.
#
# Engines take the mirror differently: podman reads a registries.conf file,
# containerd takes --hosts-dir as an argument. Hence two separate functions
# instead of one with a boolean flag.

# kubectl exec into the bench pod. Socket-native engines use this directly;
# Podman's native benchmark uses the matching podman-node host instead because
# the RIID image deliberately has no podman CLI.
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

# Registry address for an engine that runs on the NODE (podman via
# CONTAINER_HOST, containerd, Porto), not inside the pod. The host netns has no
# cluster resolver, so a *.svc.cluster.local name does not resolve there — but
# the Service ClusterIP is routable, because kube-proxy programs its rules in
# that very namespace. So the name is turned into its ClusterIP here instead of
# asking the operator to paste a NodePort by hand.
riid_registry_node_host() {
  local host name ns port rest ip
  # Resolved once per run by run-pull-scenario.sh: the backend is a fresh process
  # for every image, and a kubectl call here would land inside the measured window.
  if [[ -n "${REGISTRY_NODE_PULL_HOST:-}" ]]; then
    printf '%s\n' "$REGISTRY_NODE_PULL_HOST"
    return 0
  fi
  host="$(riid_registry_pull_host)"
  [[ -n "$host" ]] || { printf '\n'; return 0; }

  case "$host" in
    *.svc|*.svc.cluster.local|*.svc:*|*.svc.cluster.local:*) ;;
    *) printf '%s\n' "$host"; return 0 ;;
  esac

  port=""
  if [[ "$host" == *:* ]]; then
    port="${host##*:}"
    host="${host%:*}"
  fi
  name="${host%%.*}"
  rest="${host#*.}"
  ns="${rest%%.*}"

  ip="$(kubectl -n "$ns" get svc "$name" -o jsonpath='{.spec.clusterIP}' 2>/dev/null)" || true
  if [[ -z "$ip" || "$ip" == "None" ]]; then
    echo "cannot resolve service $name in namespace $ns to a ClusterIP for a node-side engine" >&2
    echo "  set REGISTRY_PULL_HOST to an address reachable from the node (ClusterIP or NodePort)" >&2
    return 1
  fi
  printf '%s%s\n' "$ip" "${port:+:$port}"
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
