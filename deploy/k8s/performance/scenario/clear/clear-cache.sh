#!/usr/bin/env bash
# Decides which of the three caches an arm has to clear, and clears exactly those.
# Usage: clear-cache.sh <arm>            e.g. bare-podman, dfinit-containerd, riid-containerd-noprefix
#        clear-cache.sh --caches a,b,c   explicit, for anything the name cannot express
#
# Three caches, three scripts, because the pull path grows one layer at a time:
#
#   bare-*    engine pulls from the registry                        -> engines
#   dfinit-*  engine pulls through the dfdaemon proxy               -> engines + dragonfly
#   riid-*    RIID pulls (via dragonfly), then imports into engine  -> all three
#
# Clearing less than the arm uses leaves a warm layer that no timing will reveal;
# clearing more only costs time, so an unknown arm name clears everything.
#
# Env:
#   CLEAR_DRY_RUN=1   print the plan and exit
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() { echo "usage: clear-cache.sh <arm> | clear-cache.sh --caches engines,dragonfly,riid" >&2; exit 2; }

caches=""
case "${1:-}" in
  "") usage ;;
  --caches)
    caches="${2:-}"; [ -n "$caches" ] || usage ;;
  -*) usage ;;
  *)
    arm="$1"
    case "$arm" in
      bare-*)   caches="engines" ;;
      dfinit-*) caches="engines,dragonfly" ;;
      riid-*)   caches="engines,dragonfly,riid" ;;
      *)
        echo "clear-cache: unknown arm '$arm', clearing everything" >&2
        caches="engines,dragonfly,riid" ;;
    esac ;;
esac

declare -A SCRIPT=(
  [engines]="$HERE/clear-cache-engines.sh"
  [dragonfly]="$HERE/clear-cache-dragonfly.sh"
  [riid]="$HERE/clear-cache-riid.sh"
)

# Order matters: RIID and the engines hold the images, Dragonfly holds the pieces
# and the index over them. Dragonfly goes last because its control-plane reset
# also restarts the data plane, and a restart after the stores are wiped is what
# keeps content and index from disagreeing.
ORDER=(engines riid dragonfly)

selected=()
IFS=, read -r -a want <<< "$caches"
for c in "${ORDER[@]}"; do
  for w in "${want[@]}"; do
    [ "$c" = "$w" ] && selected+=("$c")
  done
done
((${#selected[@]})) || { echo "clear-cache: nothing selected from '$caches'" >&2; exit 2; }

echo "clear-cache: ${arm:-explicit} -> ${selected[*]}"
if [[ "${CLEAR_DRY_RUN:-0}" == 1 ]]; then
  for c in "${selected[@]}"; do echo "  would run ${SCRIPT[$c]}"; done
  exit 0
fi

failed=0
for c in "${selected[@]}"; do
  s="${SCRIPT[$c]}"
  [ -x "$s" ] || [ -f "$s" ] || { echo "clear-cache: missing $s" >&2; exit 1; }
  echo ">>> clear-cache: $c"
  bash "$s" || { echo "clear-cache: $c FAILED" >&2; failed=1; }
done

if ((failed != 0)); then
  echo "clear-cache: one or more caches were not cleared - do not run the arm" >&2
  exit 1
fi
echo "clear-cache: cleared ${selected[*]}"
