#!/usr/bin/env bash
# First check of a cluster bench, before any arm: one image, pulled by every pod
# at once, must arrive FASTER through RIID than through the bare engine. If p2p
# does not win here it will not win on the matrix either, and a night of arms
# would measure a stand that never served a piece between peers.

# The probe image must share no layer with the dataset, or a warm layer decides
# the comparison instead of p2p. Checked 2026-09-13 against every repository in
# the Selectel CR mirror: 0 of cp-kafka's 22 layers appear there, by diff_id
# (blob digests differ - the mirror recompresses). Re-check before changing it.

# Env:
#   PROBE_IMAGE / PROBE_TAG  default confluentinc/cp-kafka:latest
#   ENGINE                   podman | containerd | porto (default containerd)
#   SMOKE_MIN_GAIN           RIID must beat bare by this factor (1.0 = merely faster)
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PERF_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

PROBE_IMAGE="${PROBE_IMAGE:-confluentinc/cp-kafka}"
PROBE_TAG="${PROBE_TAG:-latest}"
ENGINE="${ENGINE:-containerd}"
SMOKE_MIN_GAIN="${SMOKE_MIN_GAIN:-1.0}"
OUT_DIR="${SMOKE_OUT_DIR:-$PERF_DIR/output}"

say() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# Wall clock for the one image: the AGGREGATE row is first pod start to last pod
# finish, which is when the flight is actually ready. A median over pods would
# hide the straggler that p2p is supposed to remove.
aggregate_ms() {
  awk -F, 'NR>1 && $4=="AGGREGATE" { print $8 }' "$1" | head -1
}

run_arm() {
  local backend="$1" tsv="$2"
  say "  clearing caches for $backend"
  if ! bash "$SCRIPT_DIR/clear/clear-cache.sh" "$([ "$backend" = riid ] && echo riid || echo engine)" \
      >"$tsv.clear.log" 2>&1; then
    say "  clear-cache FAILED - see $tsv.clear.log"
    return 1
  fi
  say "  pulling $PROBE_IMAGE:$PROBE_TAG through $backend-$ENGINE"
  make -C "$PERF_DIR" run \
    BACKEND="$backend" ENGINE="$ENGINE" \
    BACKEND_LABEL="smoke-$backend-$ENGINE" \
    DATASET_FILE= IMAGE_REPOSITORY="$PROBE_IMAGE" IMAGE_REFERENCE="$PROBE_TAG" \
    OUTPUT_TSV="$tsv" >"$tsv.run.log" 2>&1
}

bare_tsv="$OUT_DIR/smoke-bare-$ENGINE.tsv"
riid_tsv="$OUT_DIR/smoke-riid-$ENGINE.tsv"
mkdir -p "$OUT_DIR"

say "p2p smoke: $PROBE_IMAGE:$PROBE_TAG on $ENGINE"
run_arm bare "$bare_tsv" || { say "SMOKE FAILED: the baseline arm did not run"; exit 1; }
run_arm riid "$riid_tsv" || { say "SMOKE FAILED: the RIID arm did not run"; exit 1; }

bare_ms="$(aggregate_ms "$bare_tsv")"
riid_ms="$(aggregate_ms "$riid_tsv")"
if ! [[ "$bare_ms" =~ ^[0-9]+$ ]] || ! [[ "$riid_ms" =~ ^[0-9]+$ ]]; then
  say "SMOKE FAILED: no AGGREGATE row (bare='${bare_ms:-none}' riid='${riid_ms:-none}')"
  exit 1
fi

gain="$(awk -v b="$bare_ms" -v r="$riid_ms" 'BEGIN { printf "%.2f", (r > 0 ? b / r : 0) }')"
say "bare ${bare_ms}ms, riid ${riid_ms}ms, gain ${gain}x (need > ${SMOKE_MIN_GAIN}x)"
if awk -v g="$gain" -v m="$SMOKE_MIN_GAIN" 'BEGIN { exit !(g > m) }'; then
  say "SMOKE OK: p2p is serving - the matrix is worth running"
  exit 0
fi
say "SMOKE FAILED: RIID is not faster than the bare engine on a shared image."
say "  p2p is not serving pieces between peers. Check the scheduler is settled and"
say "  the seed store was really cleared before spending a night on the matrix."
exit 1
