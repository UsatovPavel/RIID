#!/usr/bin/env bash
# Overnight block: zstd on podman, and the fixed prefix import against
# non-prefix. Runs after whatever series is already on the stand.
#
# Design notes, so the numbers are comparable and not just numerous:
#
# 1. Arms alternate gzip/zstd and prefix/non-prefix inside a round instead of
#    running all of one then all of the other. The stand drifts over a night
#    (seed cache, disk, VM scheduling); alternating spreads that drift across
#    both sides of every comparison rather than loading it onto whichever ran
#    last. The single-run zstd result this is meant to check was +59 % - and the
#    stand's own run-to-run spread within one unchanged version reaches 59 %.
#
# 2. Two rounds, so every cell ends at n>=2 and a delta can at least be told
#    apart from a single outlier.
#
# 3. The prefix pair is measured on containerd, not podman. PodmanRuntimeAdapter
#    declines prefix import whenever it holds a socket client, so a podman
#    prefix arm only runs if the stand also forces CLI mode - which changes the
#    transport at the same time as the flag. That comparison would confound the
#    two. containerd takes prefix and non-prefix over one transport, so the flag
#    is the only thing that differs.
set -uo pipefail
STAND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE="${STAND_DIR}/queue-state"
LOG="${STATE}/queue.log"
ROUNDS="${ROUNDS:-2}"

# Do not fight another series for the stand.
while pgrep -f "repeat-zstd-vs-gzip.sh" >/dev/null 2>&1; do sleep 120; done
echo "OVERNIGHT: previous series clear, starting at $(date +%H:%M)" >>"$LOG"

run_arm() {
  local arm="$1" r="$2"
  echo "OVERNIGHT: round $r/$ROUNDS starting $arm at $(date +%H:%M)" >>"$LOG"
  rm -f "${STATE}/${arm}.done" "${STATE}/${arm}.attempts"
  ARMS="$arm" "${STAND_DIR}/arm-queue.sh"
  echo "OVERNIGHT: round $r/$ROUNDS $arm exited rc=$? at $(date +%H:%M)" >>"$LOG"
}

for r in $(seq 1 "$ROUNDS"); do
  # zstd on podman: baseline, P2P-through-dfinit, and RIID itself.
  for pair in "bare-podman bare-podman-zstd" \
              "dfinit-podman dfinit-podman-zstd" \
              "riid-podman riid-podman-zstd"; do
    for arm in $pair; do run_arm "$arm" "$r"; done
  done
  # the fixed prefix import against non-prefix, same engine, same transport
  for arm in riid-containerd riid-containerd-prefix; do run_arm "$arm" "$r"; done
done
echo "OVERNIGHT: series complete at $(date +%H:%M)" >>"$LOG"
