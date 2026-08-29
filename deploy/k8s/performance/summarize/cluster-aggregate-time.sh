#!/usr/bin/env bash
# Image download time across the cluster (recreate is the only scenario).
# Uses the AGGREGATE rows only: first pod start to last pod finish.
# The arm is identified by the file, not by a literal in the backend column: that
# column now holds a label such as riid-podman / dfinit-containerd, shared by
# every arm of the matrix.
#
# Usage:
#   cluster-aggregate-time.sh <arm_a.tsv> <arm_b.tsv>

set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  echo "Usage: $0 <arm_a.tsv> <arm_b.tsv>" >&2
  exit 2
fi

A_FILE="$1"
B_FILE="$2"

for f in "$A_FILE" "$B_FILE"; do
  if [[ ! -f "$f" ]]; then
    echo "$0: file not found: $f" >&2
    exit 1
  fi
done

arm_label() {
  awk -F ',' 'NF == 9 && $5 != "backend" { print $5; exit }' "$1"
}

extract_registry_tx() {
  local f="$1"
  grep "^# registry_tx_bytes_delta:" "$f" | tail -1 | awk '{print $3}'
}

LABEL_A="$(arm_label "$A_FILE")"; LABEL_A="${LABEL_A:-a}"
LABEL_B="$(arm_label "$B_FILE")"; LABEL_B="${LABEL_B:-b}"

a_tx="$(extract_registry_tx "$A_FILE" || echo "-")"
b_tx="$(extract_registry_tx "$B_FILE" || echo "-")"

awk -v a_tx="$a_tx" -v b_tx="$b_tx" -v la="$LABEL_A" -v lb="$LABEL_B" 'BEGIN {
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

    img = c[3]
    duration_ms = c[8] + 0
    exit_code = c[9] + 0

    # Extract just the image name without riid/ prefix
    gsub(/^riid\//, "", img)

    if (NR == FNR) {
        a_time[img] = duration_ms
        a_ok[img] = (exit_code == 0)
    } else {
        b_time[img] = duration_ms
        b_ok[img] = (exit_code == 0)
    }
}

END {
    print "image", la "_ms", la "_sec", lb "_ms", lb "_sec", "ratio", "time_change_vs_" lb
    print "────────────────────────────────────────────────────────────────────────────────────"

    # Collect all images
    for (img in a_time) all_imgs[img] = 1
    for (img in b_time) all_imgs[img] = 1

    # Sort images
    n = asorti(all_imgs, sorted)

    total_a = 0
    total_b = 0
    count = 0

    for (i = 1; i <= n; i++) {
        img = sorted[i]

        r_ms = a_time[img] + 0
        p_ms = b_time[img] + 0

        if (r_ms > 0 && p_ms > 0) {
            r_sec = r_ms / 1000
            p_sec = p_ms / 1000
            ratio = r_ms / p_ms
            # Time change vs baseline (Podman): negative = less time (better), positive = more time (worse)
            time_change = (r_ms - p_ms) / p_ms * 100

            printf "%s\t%.0f\t%.2f\t%.0f\t%.2f\t%.4f\t%+.1f%%\n",
                img, r_ms, r_sec, p_ms, p_sec, ratio, time_change

            total_a += r_ms
            total_b += p_ms
            count++
        }
    }

    print "────────────────────────────────────────────────────────────────────────────────────"
    printf "TOTAL (%d images)\t%.0f\t%.2f\t%.0f\t%.2f\t%.4f\t%+.1f%%\n",
        count, total_a, total_a/1000, total_b, total_b/1000,
        total_a/total_b, (total_a - total_b) / total_b * 100

    print ""
    print "Registry traffic:"
    if (a_tx != "-" && b_tx != "-") {
        a_gib = a_tx / 1024 / 1024 / 1024
        b_gib = b_tx / 1024 / 1024 / 1024
        reduction = (1 - a_tx / b_tx) * 100
        printf "  %s: %.2f GiB\n", la, a_gib
        printf "  %s: %.2f GiB\n", lb, b_gib
        printf "  Reduction: %.2f%%\n", reduction
    } else {
        print "  (not available)"
    }
}' "$A_FILE" "$B_FILE"
