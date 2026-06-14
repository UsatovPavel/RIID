#!/usr/bin/env bash
# Сравнение времени загрузки образов в кластере (recreate mode).
# Использует только AGGREGATE строки - время от старта первого пода до завершения последнего.
#
# Usage:
#   cluster-aggregate-time.sh <riid_log> <podman_log>

set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  echo "Usage: $0 <riid_log> <podman_log>" >&2
  exit 2
fi

RIID_LOG="$1"
PODMAN_LOG="$2"

for f in "$RIID_LOG" "$PODMAN_LOG"; do
  if [[ ! -f "$f" ]]; then
    echo "$0: file not found: $f" >&2
    exit 1
  fi
done

extract_registry_tx() {
  local f="$1"
  grep "^# registry_tx_bytes_delta:" "$f" | tail -1 | awk '{print $3}'
}

riid_tx="$(extract_registry_tx "$RIID_LOG" || echo "-")"
podman_tx="$(extract_registry_tx "$PODMAN_LOG" || echo "-")"

awk -v riid_tx="$riid_tx" -v podman_tx="$podman_tx" 'BEGIN {
    FS = ","
    OFS = "\t"
}

# Skip non-data lines
/^Running / { next }
/^nohup:/ { next }
/^make/ { next }
/^#/ { next }

{
    line = $0
    sub(/\r$/, "", line)
    n = split(line, c, ",")
    if (n != 9) next
}

# Only process AGGREGATE rows
{
    pod = c[4]
    if (pod != "AGGREGATE") next

    backend = c[5]
    img = c[3]
    duration_ms = c[8] + 0
    exit_code = c[9] + 0

    # Extract just the image name without riid/ prefix
    gsub(/^riid\//, "", img)

    if (backend == "riid") {
        riid_time[img] = duration_ms
        riid_ok[img] = (exit_code == 0)
    } else if (backend == "podman") {
        podman_time[img] = duration_ms
        podman_ok[img] = (exit_code == 0)
    }
}

END {
    print "image", "riid_ms", "riid_sec", "podman_ms", "podman_sec", "ratio", "time_change_vs_podman"
    print "────────────────────────────────────────────────────────────────────────────────────"

    # Collect all images
    for (img in riid_time) all_imgs[img] = 1
    for (img in podman_time) all_imgs[img] = 1

    # Sort images
    n = asorti(all_imgs, sorted)

    total_riid = 0
    total_podman = 0
    count = 0

    for (i = 1; i <= n; i++) {
        img = sorted[i]

        r_ms = riid_time[img] + 0
        p_ms = podman_time[img] + 0

        if (r_ms > 0 && p_ms > 0) {
            r_sec = r_ms / 1000
            p_sec = p_ms / 1000
            ratio = r_ms / p_ms
            # Time change vs baseline (Podman): negative = less time (better), positive = more time (worse)
            time_change = (r_ms - p_ms) / p_ms * 100

            printf "%s\t%.0f\t%.2f\t%.0f\t%.2f\t%.4f\t%+.1f%%\n",
                img, r_ms, r_sec, p_ms, p_sec, ratio, time_change

            total_riid += r_ms
            total_podman += p_ms
            count++
        }
    }

    print "────────────────────────────────────────────────────────────────────────────────────"
    printf "TOTAL (%d images)\t%.0f\t%.2f\t%.0f\t%.2f\t%.4f\t%+.1f%%\n",
        count, total_riid, total_riid/1000, total_podman, total_podman/1000,
        total_riid/total_podman, (total_riid - total_podman) / total_podman * 100

    print ""
    print "Registry traffic:"
    if (riid_tx != "-" && podman_tx != "-") {
        riid_gib = riid_tx / 1024 / 1024 / 1024
        podman_gib = podman_tx / 1024 / 1024 / 1024
        reduction = (1 - riid_tx / podman_tx) * 100
        printf "  Riid:   %.2f GiB\n", riid_gib
        printf "  Podman: %.2f GiB\n", podman_gib
        printf "  Reduction: %.2f%%\n", reduction
    } else {
        print "  (not available)"
    }
}' "$RIID_LOG" "$PODMAN_LOG"
