#!/usr/bin/env bash
# Does an arm measure what its name claims? Kept here rather than in bench/k8s,
# which is the laptop stand's VirtualBox wrapper, so a Selectel run has a gate too.
# Usage: validate-arm.sh <arm> <tsv> [logdir]  - logdir is the arm's exported logs,
# required for riid-*/dfinit-*, ignored for bare-*. Env: EXPECTED_IMAGES (default 20)

set -uo pipefail

ARM="${1:?arm name required}"
TSV="${2:?tsv path required}"
LOGDIR="${3:-}"
EXPECTED_IMAGES="${EXPECTED_IMAGES:-20}"

fail=0
ok()   { printf '  OK   %s\n' "$*"; }
bad()  { printf '  FAIL %s\n' "$*"; fail=$((fail+1)); }
note() { printf '  --   %s\n' "$*"; }

printf 'validate %s\n' "$ARM"

if [ ! -s "$TSV" ]; then
  bad "no TSV at $TSV"
  printf '%s: INVALID (%d failed)\n' "$ARM" "$fail"
  exit 1
fi

# A failed pull finishes in ~0.3 s and looks like a cache hit under any MB/s
# heuristic, so exit codes are checked before anything about throughput.
images=$(awk -F, 'NR>1 && $4=="AGGREGATE"' "$TSV" | wc -l)
failures=$(awk -F, 'NR>1 && $9!=0 && $9!=""' "$TSV" | wc -l)
[ "$images" -eq "$EXPECTED_IMAGES" ] && ok "images $images/$EXPECTED_IMAGES" \
  || bad "images $images/$EXPECTED_IMAGES"
[ "$failures" -eq 0 ] && ok "no non-zero exit_code" || bad "$failures rows with non-zero exit_code"

case "$ARM" in
  riid-*|dfinit-*)
    if [ -z "$LOGDIR" ] || [ ! -d "$LOGDIR" ]; then
      bad "logdir required for $ARM (dfdaemon/ and seed/ logs) - got '${LOGDIR:-<none>}'"
    else
      # dfdaemon logs "download task started" even when every task then fails: four
      # dfinit-containerd runs were recorded valid that way, 220 started and 0 done.
      # "succeeded" is the only outcome word in this log - "finished" never appears.
      started=$(grep -rh 'download task started' "$LOGDIR/dfdaemon" 2>/dev/null | wc -l)
      done_ok=$(grep -rh 'download task succeeded' "$LOGDIR/dfdaemon" 2>/dev/null | wc -l)
      [ "$done_ok" -gt 0 ] && ok "dfdaemon tasks succeeded=$done_ok (started=$started)" \
        || bad "dfdaemon tasks succeeded=0 of $started started - the arm measured a plain pull"
      # kubelet rotation truncates these logs, so both counts are a floor and
      # succeeded can exceed started. Only "> 0" is load-bearing here.
      [ "$done_ok" -gt "$started" ] && note "dfdaemon log truncated by rotation - counts are a floor"

      # Savings track seed participation: zero back-to-source means the seed tier
      # fetched nothing and served nothing, whatever the RIID-side counters say.
      btos=$(grep -rh 'need back to source response' "$LOGDIR/seed" "$LOGDIR/dfdaemon" 2>/dev/null | wc -l)
      [ "$btos" -gt 0 ] && ok "NeedBackToSource(tx)=$btos" \
        || bad "NeedBackToSource(tx)=0 - the seed tier did not participate"

      p2p=$(grep -rho 'Source fetched: p2p' "$LOGDIR/riid" 2>/dev/null | wc -l)
      reg=$(grep -rho 'Source fetched: registry' "$LOGDIR/riid" 2>/dev/null | wc -l)
      note "RIID source: p2p=$p2p registry=$reg"
      disc=$(grep -rho 'failed to close dragonfly puller' "$LOGDIR/riid" 2>/dev/null | wc -l)
      [ "$disc" -gt 0 ] && note "p2p-discarded-after-download=$disc (re-fetched from the registry)"
    fi
    ;;
esac

# Which import mode did RIID actually use? layer.import is emitted only from
# OciArchiveBuilder.streamLayers, reached only through importIncrementally, i.e.
# only on the prefix path - so its presence is the mode, not a proxy for it.
# Without this the two arms differ by filename alone and -noprefix can silently
# measure prefix mode a second time.
case "$ARM" in
  riid-*)
    if [ -n "$LOGDIR" ] && [ -d "$LOGDIR" ]; then
      li=$(grep -rho 'layer\.import' "$LOGDIR/riid" 2>/dev/null | wc -l)
      case "$ARM" in
        *-noprefix)
          [ "$li" -eq 0 ] && ok "prefix import off: layer.import=0" \
            || bad "layer.import=$li - prefixImport was ON, this arm measured prefix mode" ;;
        *)
          # A single-layer image never takes the prefix path (supportsIncrementalImport
          # needs layers>1), so only "> 0" is load-bearing, not a per-image count.
          [ "$li" -gt 0 ] && ok "prefix import on: layer.import=$li" \
            || bad "layer.import=0 - prefixImport was OFF, this arm is not a prefix run" ;;
      esac
    fi
    ;;
esac

# Did the arm start cold? The driver wipes the containerd riid-bench namespace on
# every node and records what was left; a node that kept the dataset makes the arm
# a warm run wearing a cold label, and nothing else in this gate would notice.
if [ -n "$LOGDIR" ] && [ -f "$LOGDIR/cache-clear.log" ]; then
  # Any containerd namespace, not just riid-bench: RIID imports into "default",
  # so a check scoped to riid-bench proved an unused namespace was empty.
  # Count nodes, not namespaces: a brand-new stand has no containerd namespace
  # besides k8s.io, so the per-namespace lines are absent exactly when the node
  # is most certainly cold. Each node prints one verdict either way.
  nodes_clean=$(grep -cE 'node containerd images left: 0 ' "$LOGDIR/cache-clear.log" 2>/dev/null)
  nodes_dirty=$(grep -cE 'node containerd images left: [1-9]' "$LOGDIR/cache-clear.log" 2>/dev/null)
  left=$(grep -cE 'images left in [^:]+: [1-9]' "$LOGDIR/cache-clear.log" 2>/dev/null)
  failed_clean=$(grep -cE 'FAILED (containerd|podman) ' "$LOGDIR/cache-clear.log" 2>/dev/null)
  # Every pod that pulled must sit on a node the clear actually reached.
  pods=$(awk -F, 'NR>1 && $4!="AGGREGATE"{print $4}' "$TSV" | sort -u | grep -c .)
  if [ "$nodes_clean" -ge "$pods" ] && [ "$nodes_dirty" -eq 0 ] && [ "$left" -eq 0 ] \
     && [ "$failed_clean" -eq 0 ]; then
    ok "cold start: $nodes_clean node(s) empty, covering $pods pulling pod(s)"
  else
    bad "cold start not proven: $nodes_clean node(s) empty vs $pods pod(s), $nodes_dirty node(s) and $left namespace(s) still holding images, $failed_clean cleanup failure(s)"
  fi
else
  note "no cache-clear.log in the logdir - cold start unverified"
fi

egress=$(grep registry_tx_bytes_delta "$TSV" 2>/dev/null | awk -F'\t' '{printf "%.2f", $2/1073741824}')
[ -n "$egress" ] && note "egress: $egress GiB"
awk -F, 'NR>1 && $4=="AGGREGATE"{s+=$8} END{if(s>0) printf "  --   sum AGGREGATE: %.1f s\n", s/1000}' "$TSV"

if [ "$fail" -eq 0 ]; then
  printf '%s: VALID\n' "$ARM"
  exit 0
fi
printf '%s: INVALID (%d check(s) failed)\n' "$ARM" "$fail"
exit 1
