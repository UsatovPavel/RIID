#!/usr/bin/env bash
# podman driver. The engine is a daemon on the node (podman.socket, installed by
# src/engines/podman-node.yaml). The RIID image has no podman binary, so native
# benchmark commands execute the host client through the podman-node installer.
#
# PodmanRuntimeAdapter itself speaks Libpod HTTP directly over CONTAINER_HOST.
# Both paths operate on the same node image store.
#
# Two consequences of the engine living on the node, both of which used to be
# handled in the pod and are now checked instead:
#
#   1. The pull must happen in the HOST netns, and getting there takes nsenter:
#      chroot swaps the filesystem only, so `chroot /host podman pull` still runs
#      in the installer pod's Calico netns. The cluster resolver does not exist
#      there either, so the reference carries the registry's ClusterIP rather
#      than its *.svc.cluster.local name - see riid_registry_node_host.
#   2. dfinit edits the node's own /etc/containers/registries.conf, which is the
#      file the daemon reads. The baseline arm can no longer be pointed at a
#      private copy through CONTAINERS_REGISTRIES_CONF, because that variable
#      would be set on the client while the pull runs in the service. So the
#      baseline asserts the mirror is absent instead of arranging for it.
#
# Env:
#   PODMAN_TLS_VERIFY  force true|false; defaults to false for the local HTTP
#                        registry, true otherwise

_podman_tls_verify() {
  local v="${PODMAN_TLS_VERIFY:-${REGISTRY_TLS_VERIFY:-}}"
  if [[ -z "$v" ]]; then
    if riid_registry_is_plain_http; then v=false; else v=true; fi
  fi
  printf '%s\n' "$v"
}

_podman_node_exec() {
  local riid_pod="$1" node node_pod
  shift
  node="$(kubectl -n "$NS" get pod "$riid_pod" -o jsonpath='{.spec.nodeName}')"
  node_pod="$(kubectl -n "$NS" get pods -l app.kubernetes.io/name=podman-node \
    --field-selector "spec.nodeName=$node,status.phase=Running" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  if [[ -z "$node_pod" ]]; then
    echo "no podman-node pod on node=$node for RIID pod=$riid_pod" >&2
    return 1
  fi
  # nsenter into PID 1's netns (hostPID is on), not just chroot: dfinit points
  # podman at the dfdaemon proxy on 127.0.0.1:4001, which lives in the HOST
  # netns. From the pod's own netns that port is closed, podman reports
  # "connection refused" and falls back to the registry without failing - which
  # is how a whole dfinit-podman arm measured a plain pull on 2026-09-11 while
  # `podman info` still listed the mirror, because reading config needs no socket.
  kubectl -n "$NS" exec -c installer "$node_pod" -- nsenter -t 1 -n chroot /host "$@"
}

# What the daemon resolved, not what some file says: this is the engine's own
# view of its registries, mirrors included (libpod/info.go:53 fills it from
# sysregistriesv2.GetRegistries).
_podman_registries() {
  _podman_node_exec "$1" podman info --format '{{json .Registries}}'
}

engine_preflight() {
  local pod="$1"
  riid_engine_exec "$pod" sh -ec '
    if command -v podman >/dev/null 2>&1; then
      echo "podman must not be installed in the RIID container" >&2
      exit 1
    fi
    if ! response="$(curl --fail --silent --show-error --unix-socket /run/podman/podman.sock http://d/_ping)"; then
      echo "Podman API is not reachable through /run/podman/podman.sock" >&2
      exit 1
    fi
    if [ "$response" != OK ]; then
      echo "unexpected Podman _ping response: $response" >&2
      exit 1
    fi
  '
  if ! _podman_node_exec "$pod" podman info >/dev/null; then
    echo "podman info failed on the node behind RIID pod=$pod" >&2
    _podman_node_exec "$pod" sh -c \
      'systemctl --no-pager --full status podman.socket podman.service; ss -lx | grep podman || true' >&2 || true
    return 1
  fi
}

# With no host the reference stays short: podman completes it through
# unqualified-search-registries, so this is a working case, not an error.
engine_ref() {
  local repo="$1" tag="$2" host
  host="$(riid_registry_node_host)" || return 1
  if [[ -n "$host" ]]; then
    printf '%s/%s:%s\n' "${host%/}" "$repo" "$tag"
  else
    printf '%s:%s\n' "$repo" "$tag"
  fi
}

engine_pull() {
  local pod="$1" ref="$2"
  _podman_node_exec "$pod" podman pull --tls-verify="$(_podman_tls_verify)" "$ref" >/dev/null
}

engine_pull_mirrored() {
  local pod="$1" ref="$2"
  _podman_node_exec "$pod" podman pull --tls-verify="$(_podman_tls_verify)" "$ref" >/dev/null
}

# Whether the registry under test is mirrored - not whether the file mentions the
# proxy anywhere. scripts/values.yaml carries a hardcoded crio block for
# 10.96.5.146:5000 (an old stand's registry) and dfinit.enable is unconditional,
# so every stand gets a mirror for a registry it does not use. A substring check
# passes on that and the arm measures a plain pull, which is how every recorded
# dfinit-podman run ended up with baseline egress.
_podman_mirror_of() {
  local pod="$1" host
  host="$(riid_registry_node_host)" || return 1
  [ -n "$host" ] || { echo "registry host is empty, set REGISTRY_PULL_HOST" >&2; return 1; }
  _podman_registries "$pod" | HOST="$host" python3 -c '
import json, os, sys
host = os.environ["HOST"]
try:
    regs = json.load(sys.stdin)
except Exception:
    sys.exit(2)
entry = regs.get(host)
if not isinstance(entry, dict):
    print("")
    sys.exit(0)
print(",".join(m.get("Location", "") for m in (entry.get("Mirrors") or [])))
'
}

# Configured is not the same as reachable. `podman info` parses a file and needs
# no socket, so it kept reporting the mirror while every pull got "connection
# refused" from it and fell back to the registry. Probe the proxy from the same
# netns the pull runs in, which is the only place its reachability matters.
_podman_mirror_reachable() {
  local pod="$1" loc="$RIID_DFINIT_PROXY_LOCATION"
  _podman_node_exec "$pod" sh -ec "
    h=\${0%%:*}; p=\${0##*:}
    (command -v nc >/dev/null 2>&1 && nc -z -w5 \"\$h\" \"\$p\") ||
    (command -v curl >/dev/null 2>&1 && curl -s -o /dev/null --max-time 5 \"http://\$h:\$p/v2/\")
  " "$loc"
}

engine_mirror_check() {
  local pod="$1" host mirrors
  host="$(riid_registry_node_host)"
  mirrors="$(_podman_mirror_of "$pod")" || return 1
  case ",$mirrors," in
    *",$RIID_DFINIT_PROXY_LOCATION,"*)
      if ! _podman_mirror_reachable "$pod"; then
        echo "dfinit mirror '$RIID_DFINIT_PROXY_LOCATION' is configured for '$host' but" >&2
        echo "  unreachable from where the pull runs: podman would log 'connection refused'" >&2
        echo "  and silently fall back to the registry, measuring a plain pull." >&2
        echo "  The dfdaemon proxy listens in the HOST netns - check that the engine" >&2
        echo "  command enters it (nsenter -t 1 -n), not just chroot /host." >&2
        return 1
      fi
      return 0 ;;
  esac
  echo "dfinit mirror not found for the registry under test: pod=$pod, registry='$host'" >&2
  echo "  its mirrors: [${mirrors:-none}], expected '$RIID_DFINIT_PROXY_LOCATION'" >&2
  echo "  dfinit writes /etc/containers/registries.conf on the NODE; check client.dfinit" >&2
  echo "  in values - a block pinned to another address mirrors the wrong registry" >&2
  return 1
}

# The mirror image of the check above, for the baseline arm. Scoped to the same
# registry: a stale block for some other address does not route our pulls, so it
# must not fail the baseline - only a mirror on the registry under test does.
engine_no_mirror_check() {
  local pod="$1" host mirrors
  host="$(riid_registry_node_host)"
  mirrors="$(_podman_mirror_of "$pod")" || return 1
  case ",$mirrors," in
    *",$RIID_DFINIT_PROXY_LOCATION,"*) ;;
    *) return 0 ;;
  esac
  echo "baseline arm is contaminated: pod=$pod still mirrors registry '$host' through" >&2
  echo "  '$RIID_DFINIT_PROXY_LOCATION'. Disable client.dfinit and restore the node's" >&2
  echo "  /etc/containers/registries.conf from the pristine copy taken at install:" >&2
  echo "  /etc/containers/registries.conf.riid-baseline (src/engines/podman-node.yaml)" >&2
  return 1
}

# --volumes: a pull creates no anonymous volumes, so it does not affect the
# measurement, but it matches scenario/clear/clear-cache-engines.sh —
# one meaning of "clean". Now wipes the node's store, which is the store both
# the baseline and the RIID import write into.
engine_clear_cache() {
  _podman_node_exec "$1" podman system prune -af --volumes >/dev/null
}
