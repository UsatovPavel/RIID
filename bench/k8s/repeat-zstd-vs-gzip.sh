#!/usr/bin/env bash
# Paired repeat of dfinit-containerd against the gzip and the zstd copy of the
# same 20 images.
#
# Why: the single zstd run came out +534.8 s over the gzip run (1434.9 vs
# 900.1), and that was written up as "zstd slows the P2P path down". But the
# measured run-to-run spread on this stand is 42-59 % on wall time within one
# unchanged version, and +59 % is exactly what this delta is. One run per
# format cannot tell a format effect from the stand's own noise.
#
# The arms alternate rather than running all-zstd then all-gzip, so a drift in
# the stand (a warmer seed, a fuller disk, a slower VM) hits both formats
# equally instead of loading onto whichever format ran last.
set -uo pipefail
STAND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROUNDS="${ROUNDS:-2}"
STATE="${STAND_DIR}/queue-state"
LOG="${STATE}/queue.log"

for r in $(seq 1 "$ROUNDS"); do
  for arm in dfinit-containerd dfinit-containerd-zstd; do
    echo "REPEAT-ZSTD: round $r/$ROUNDS starting $arm at $(date +%H:%M)" >>"$LOG"
    rm -f "${STATE}/${arm}.done" "${STATE}/${arm}.attempts"
    ARMS="$arm" "${STAND_DIR}/arm-queue.sh"
    echo "REPEAT-ZSTD: round $r/$ROUNDS $arm exited rc=$? at $(date +%H:%M)" >>"$LOG"
  done
done
echo "REPEAT-ZSTD: series complete at $(date +%H:%M)" >>"$LOG"
