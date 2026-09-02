#!/usr/bin/env bash
# One-shot runner for the dfinit arm of AGENT-89, once the cluster is up
# (bench/setup-env.sh already ran successfully). Does everything that needs
# no further human interaction: applies the dfinit overlay (proxy.rules +
# podman configPath pinned, see dfinit-values-overlay.yaml), runs the
# standalone dfinit_bench.py, and captures the dragonfly-client pod's own
# (dfdaemon, Rust) logs for the same window -- so the parallel-fetch
# question in zOptimization/SessionSummary_AGENT-89.md §5 can be closed out
# without a second round-trip.
#
# Usage: bash bench/dfinit/run_dfinit_arm.sh
# Requires: cluster already up and reachable (kubectl get pods -n
# dragonfly-system succeeds), Dragonfly release already installed by
# setup-env.sh. Does not itself need sudo -- helm/kubectl use the
# already-chowned kubeconfig setup-env.sh leaves behind.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"
NAMESPACE="dragonfly-system"
SCRATCH_VALUES="$(mktemp)"
trap 'rm -f "$SCRATCH_VALUES"' EXIT

log() { printf '>>> %s\n' "$*"; }

log "[1/5] preflight: cluster reachable?"
kubectl get pods -n "$NAMESPACE" >/dev/null

log "[2/5] snapshot current release values + chart version"
CHART_VERSION="$(helm list -n "$NAMESPACE" -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["chart"].rsplit("-",1)[1])')"
helm get values -n "$NAMESPACE" dragonfly -o yaml > "$SCRATCH_VALUES"

log "[3/5] apply dfinit overlay (proxy.rules + podman configPath pinned) -- chart $CHART_VERSION"
helm upgrade -n "$NAMESPACE" dragonfly dragonfly/dragonfly --version "$CHART_VERSION" \
  -f "$SCRATCH_VALUES" \
  -f bench/dfinit/dfinit-values-overlay.yaml \
  --wait --timeout 5m

log "waiting for dfdaemon socket"
for _ in $(seq 1 30); do
  [ -S /var/run/dragonfly/dfdaemon.sock ] && break
  sleep 2
done
[ -S /var/run/dragonfly/dfdaemon.sock ] || { echo "!!! dfdaemon socket never appeared" >&2; exit 1; }

CLIENT_POD="$(kubectl get pods -n "$NAMESPACE" -l component=client -o jsonpath='{.items[0].metadata.name}')"
log "dfinit initContainer log (confirms engine + registries picked up):"
kubectl logs -n "$NAMESPACE" "$CLIENT_POD" -c dfinit || true

log "[4/5] run dfinit_bench.py (skip-fresh-check: this cluster was already verified fresh by setup-env.sh)"
python3 bench/dfinit/dfinit_bench.py --label dfinit-fixed --skip-fresh-check

log "[5/5] capture dfdaemon (dragonfly-client) pod logs for the same run"
DFDAEMON_LOG="bench/dfinit/results/$(date -u +%Y%m%dT%H%M%SZ)_dfdaemon.log"
kubectl logs -n "$NAMESPACE" "$CLIENT_POD" > "$DFDAEMON_LOG" 2>&1 || true
log "dfdaemon logs saved: $DFDAEMON_LOG"

log "done. Compare bench/dfinit/results/*_dfinit-fixed.tsv against the no-mirror-control tsv,"
log "and grep $DFDAEMON_LOG for the python pull to see dfdaemon's own P2P behavior."
