#!/usr/bin/env bash
# One p2p verdict for every engine. Two copies of this decision is how a dfinit
# arm measured a plain pull: the config named a mirror and the proxy refused
# every connection. Sourced from common.inc.sh before the driver, so a driver
# that cannot take a mirror at all (porto) still overrides engine_mirror_check.

# The driver supplies only what its own config says:
#   engine_mirror_location <pod>          the mirror set for the registry, or empty
#   engine_exec_where_pull_runs <pod> ..  a command in the netns the pull uses

# Configured is not reachable. `podman info` and a hosts.toml both parse a file
# and need no socket, so they kept reporting the mirror while every pull got
# "connection refused" from it and silently fell back to the registry.
riid_mirror_reachable() {
  local pod="$1" loc="$RIID_DFINIT_PROXY_LOCATION"
  engine_exec_where_pull_runs "$pod" sh -ec '
    h=${0%%:*}; p=${0##*:}
    (command -v nc >/dev/null 2>&1 && nc -z -w5 "$h" "$p") ||
    (command -v curl >/dev/null 2>&1 && curl -s -o /dev/null --max-time 5 "http://$h:$p/v2/")
  ' "$loc"
}

# dfinit arms: the mirror must be configured for this registry AND answer.
engine_mirror_check() {
  local pod="$1" host mirrors
  host="$(riid_registry_node_host)"
  mirrors="$(engine_mirror_location "$pod")" || return 1
  case ",$mirrors," in
    *",$RIID_DFINIT_PROXY_LOCATION,"*)
      if ! riid_mirror_reachable "$pod"; then
        echo "dfinit mirror '$RIID_DFINIT_PROXY_LOCATION' is configured for '$host' but" >&2
        echo "  unreachable from where the pull runs: the engine would fall back to the" >&2
        echo "  registry and measure a plain pull. The dfdaemon proxy listens in the HOST" >&2
        echo "  netns - check the engine command enters it (nsenter -t 1 -n), not just chroot." >&2
        return 1
      fi
      return 0 ;;
    *)
      echo "dfinit mirror missing for '$host': configured mirrors = '${mirrors:-none}'," >&2
      echo "  expected '$RIID_DFINIT_PROXY_LOCATION' - re-run dfinit-enable for this engine." >&2
      return 1 ;;
  esac
}

# Baseline arms on engines that share one config file with the dfinit arm: the
# mirror must be really absent, or the baseline is a second dfinit run. Opt-in,
# because containerd reads certs.d only when the pull passes --hosts-dir, which
# its baseline does not - a mirror left on disk there routes nothing.
riid_no_mirror_check() {
  local pod="$1" host mirrors
  host="$(riid_registry_node_host)"
  mirrors="$(engine_mirror_location "$pod")" || return 1
  case ",$mirrors," in
    *",$RIID_DFINIT_PROXY_LOCATION,"*)
      echo "baseline arm, but '$host' still routes through the dfinit mirror" >&2
      echo "  '$RIID_DFINIT_PROXY_LOCATION' - this would measure dfinit under a bare label." >&2
      echo "  Restore the pristine config: make -C deploy/k8s/bootstrap dfinit-disable" >&2
      return 1 ;;
    *) return 0 ;;
  esac
}
