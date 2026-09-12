#!/usr/bin/env bash
# Dragonfly control-plane workarounds the arm driver runs before each arm: stale
# scheduler rows, dfdaemons pinned to a dead scheduler IP, and the settle gate.
# Sourced by selectel-arm-series.sh; expects kubectl with KUBECONFIG already set.

DF_NS="${DRAGONFLY_NAMESPACE:-dragonfly-system}"
DF_MYSQL_PASSWORD="${DRAGONFLY_MYSQL_PASSWORD:-dragonfly-root}"

declare -F say >/dev/null || say() { printf '%s\n' "$*"; }

df_mysql() {
  kubectl -n "$DF_NS" exec dragonfly-mysql-0 -- \
    mysql -uroot -p"$DF_MYSQL_PASSWORD" -N -e "$1" 2>/dev/null | tr -d '\r'
}

df_scheduler_ip() {
  kubectl -n "$DF_NS" get pod dragonfly-scheduler-0 -o jsonpath='{.status.podIP}' 2>/dev/null
}

df_scheduler_restarts() {
  kubectl -n "$DF_NS" get pod dragonfly-scheduler-0 \
    -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null || echo 0
}

# Restarts every resource first and waits after, so they roll in parallel.
# Usage: df_roll <timeout> <resource>...; non-zero if any rollout did not finish.
df_roll() {
  local timeout="$1" res rc=0
  shift
  for res in "$@"; do
    kubectl -n "$DF_NS" rollout restart "$res" >/dev/null 2>&1
  done
  for res in "$@"; do
    kubectl -n "$DF_NS" rollout status "$res" --timeout="$timeout" >/dev/null 2>&1 || rc=1
  done
  return "$rc"
}

df_clients_ready() {
  local _ total ready
  for _ in $(seq 1 20); do
    total=$(kubectl -n "$DF_NS" get pods -l app=dragonfly,component=client --no-headers 2>/dev/null | wc -l)
    ready=$(kubectl -n "$DF_NS" get pods -l app=dragonfly,component=client --no-headers 2>/dev/null | awk '$2=="1/1"' | wc -l)
    if [ "$total" -gt 0 ] && [ "$total" = "$ready" ]; then
      say "  dragonfly-client stable"
      return 0
    fi
    sleep 5
  done
  say "  WARNING: dragonfly-client not all Ready after 100s, continuing anyway"
}

# Each scheduler restart leaves its manager.scheduler row (11 stale rows once
# crashlooped every client); the manager caches that table, so a prune needs a
# manager restart, which can take the scheduler down onto a new pod IP with it.
# Usage: dragonfly_prepare <roll_data_plane 0|1> - 1 re-points dfdaemons even if it did not move.
dragonfly_prepare() {
  local roll_data_plane="${1:-0}" ip restarts new_ip new_restarts
  ip=$(df_scheduler_ip)
  [ -n "$ip" ] || { say "  dragonfly: no live scheduler IP, skipping"; return 0; }
  restarts=$(df_scheduler_restarts)
  df_mysql "DELETE FROM manager.scheduler WHERE ip != '$ip';" >/dev/null
  say "  dragonfly: scheduler rows pruned to $ip, restarting manager"
  df_roll 120s deployment/dragonfly-manager
  kubectl -n "$DF_NS" rollout status statefulset/dragonfly-scheduler --timeout=300s >/dev/null 2>&1
  new_ip=$(df_scheduler_ip)
  new_restarts=$(df_scheduler_restarts)

  if [ "$new_ip" != "$ip" ] || [ "$new_restarts" != "$restarts" ]; then
    say "  dragonfly: scheduler restarted with the manager (restarts $restarts->$new_restarts, ip $ip->${new_ip:-none})"
    [ -z "$new_ip" ] || df_mysql "DELETE FROM manager.scheduler WHERE ip != '$new_ip';" >/dev/null
    roll_data_plane=1
  fi

  # Client and seed are both dfdaemons and keep the scheduler IP they started with.
  # The cache clear rolls them before it restarts the scheduler, so they announce to
  # a dead one (2026-09-13: 2083 pieces back-to-source, 0 from a peer); a moved
  # scheduler does the same (2026-09-12: ten podman pulls hung 35 min).
  if [ "$roll_data_plane" = 1 ]; then
    say "  dragonfly: rolling client and seed onto the live scheduler"
    df_roll 300s daemonset/dragonfly-client statefulset/dragonfly-seed-client \
      || say "  WARNING: data-plane roll did not finish in 300s, continuing"
    say "  dragonfly: active scheduler rows: $(df_mysql "SELECT ip FROM manager.scheduler WHERE state='active';" | tr '\n' ' ')"
    return 0
  fi
  df_clients_ready
}

# A podman pull through the dfinit mirror hangs with no error if the scheduler moves
# mid-pull (2026-09-12: ten pulls, 35 min, zero dfdaemon tasks). "All pods Ready" held
# in both hung runs, so this waits for one active row equal to a stable scheduler IP.
dragonfly_wait_settled() {
  local tries="${1:-60}" stable=0 prev_ip="" prev_restarts="" ip restarts ready total active
  while [ "$tries" -gt 0 ]; do
    tries=$((tries - 1))
    ip=$(df_scheduler_ip)
    restarts=$(df_scheduler_restarts)
    ready=$(kubectl -n "$DF_NS" get pods --no-headers 2>/dev/null | awk '$2=="1/1"' | wc -l)
    total=$(kubectl -n "$DF_NS" get pods --no-headers 2>/dev/null | wc -l)
    active=$(df_mysql "SELECT ip FROM manager.scheduler WHERE state='active';" | tr '\n' ' ')
    active="${active% }"
    if [ -n "$ip" ] && [ "$total" -gt 0 ] && [ "$ready" = "$total" ] \
       && [ "$active" = "$ip" ] && [ "$ip" = "$prev_ip" ] && [ "$restarts" = "$prev_restarts" ]; then
      stable=$((stable + 1))
      if [ "$stable" -ge 3 ]; then
        say "  P2P settled: scheduler $ip, $ready/$total dragonfly pods Ready"
        return 0
      fi
    else
      stable=0
    fi
    prev_ip="$ip"; prev_restarts="$restarts"
    sleep 10
  done
  say "  P2P did not settle (scheduler still moving or manager row stale)"
  return 1
}
