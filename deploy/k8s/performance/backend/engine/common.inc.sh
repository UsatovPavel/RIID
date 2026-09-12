#!/usr/bin/env bash
# Shared part of the engine drivers. Sourced from backend/bare.sh and
# backend/dfinit.sh; not runnable on its own.
#
# Driver contract — every engine/<name>.inc.sh must define:
#   engine_preflight <pod>            engine reachable in the pod (binary, socket)
#   engine_ref <repo> <tag>           prints the full reference to pull
#   engine_pull <pod> <ref>           baseline pull, no dfinit mirror
#   engine_pull_mirrored <pod> <ref>  pull through the mirror dfinit wrote
#   engine_clear_cache <pod>          wipe the local image store
#
# For the p2p verdict in mirror-check.inc.sh, which implements engine_mirror_check
# once for every engine, a driver supplies only what its own config says:
#   engine_mirror_location <pod>          the mirror configured for this registry
#   engine_exec_where_pull_runs <pod> ..  a command in the netns the pull uses
# An engine that cannot take a mirror at all (Porto) overrides engine_mirror_check
# instead; a driver whose baseline shares one config file with the dfinit arm
# opts into engine_no_mirror_check (podman does, containerd needs no such check).
#
# Optional, called by riid_pull_with_retry when the driver defines it:
#   engine_drop_image <pod> <ref>     drop a partial image before a cold retry

# The workstation reaches the MKS API over the public internet, and one dropped
# TCP handshake there kills a whole arm: two AGENT-117 runs died on a single pod
# out of ten with "dial tcp <api>:6443: connect: connection timed out".
# Retried ONLY when kubectl could not establish the connection - the exec never
# reached the pod, so nothing ran and no real failure is hidden. A command that
# did run and exited non-zero passes through untouched, because masking that is
# precisely what summarize/validate-arm.sh exists to catch.
riid_kubectl() {
  local attempt=1 max="${RIID_KUBECTL_CONNECT_RETRIES:-3}" err rc
  err="$(mktemp)"
  while :; do
    rc=0
    kubectl "$@" 2>"$err" || rc=$?
    cat "$err" >&2
    if ((rc != 0)) && ((attempt < max)) && grep -qE \
        'connect: connection (timed out|refused)|connect: no route to host|Unable to connect to the server|TLS handshake timeout' "$err"; then
      echo "riid_kubectl: API unreachable, attempt $attempt/$max failed, retrying in 5s" >&2
      attempt=$((attempt + 1))
      sleep 5
      continue
    fi
    rm -f "$err"
    return "$rc"
  done
}

# kubectl exec into the bench pod. Every engine command goes through it.
riid_engine_exec() {
  local pod="$1"
  shift
  riid_kubectl -n "$NS" exec -c "$CONTAINER" "$pod" -- "$@"
}

# One transport-error list for every engine, not one per driver: the workstation
# reaches the API over a VPN and a single hiccup drops every open exec stream at
# once - three AGENT-117 arms died that way, ten pods in the same second. An
# engine's own error does not match these and still fails the arm, as it must.
riid_stream_broke() {
  grep -qE 'error reading from error stream|Copying std(out|err) failed|i/o timeout|unexpected EOF|error dialing backend' "$1"
}

# Runs a pull and retries it when the exec stream - not the pull - broke. The
# killed pull leaves partial blobs, so a driver that defines engine_drop_image
# gets a retry as cold as the first attempt; a warm resume would understate it.
# Usage: riid_pull_with_retry <pod> <ref> <command...>
riid_pull_with_retry() {
  local pod="$1" ref="$2"
  shift 2
  local attempt=1 max="${RIID_PULL_STREAM_RETRIES:-3}" err rc
  err="$(mktemp)"
  while :; do
    rc=0
    "$@" 2>"$err" || rc=$?
    cat "$err" >&2
    if ((rc != 0)) && ((attempt < max)) && riid_stream_broke "$err"; then
      echo "engine: exec stream broke on $ref (attempt $attempt/$max), retrying cold" >&2
      if declare -F engine_drop_image >/dev/null; then
        engine_drop_image "$pod" "$ref"
      fi
      attempt=$((attempt + 1))
      sleep 5
      continue
    fi
    rm -f "$err"
    return "$rc"
  done
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

# After RIID_DFINIT_PROXY_LOCATION, which it uses, and before the driver, which
# may override engine_mirror_check (Porto cannot take a mirror at all).
# shellcheck source=mirror-check.inc.sh
source "$(dirname "${BASH_SOURCE[0]}")/mirror-check.inc.sh"
