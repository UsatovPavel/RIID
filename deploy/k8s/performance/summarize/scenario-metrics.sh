#!/usr/bin/env bash
# Metrics of one bench arm: one scenario result in, three numbers out.
#
# Input is the TSV a run writes to performance/output/<source>-<engine>.tsv
# (CSV rows: scenario,mode,image,pod,backend,start_ms,end_ms,duration_ms,exit_code
# plus "# registry_tx_bytes_*" trailer lines).
#
# The three metrics:
#   registry_tx_gib     bytes the registry sent during the run, from the
#                       registry_tx_bytes_delta trailer
#   sum_mean_pull_sec   per image, the mean duration over the pods that
#                       succeeded, summed over images
#   sum_wall_clock_sec  per image, the AGGREGATE row — first pod start to last
#                       pod finish — summed over images
#
# Comparing two arms is deliberately not done here: run the script per arm and
# put the numbers side by side. "Running scenario=" progress lines and rows
# without 9 comma-separated fields are ignored.
#
# Plain awk only (no asorti), so it works with mawk as well as with gawk.
#
# Usage:
#   scenario-metrics.sh <arm.tsv>

set -euo pipefail

if [[ "$#" -ne 1 ]]; then
  echo "Usage: $0 <arm.tsv>" >&2
  exit 2
fi

FILE="$1"
if [[ ! -f "$FILE" ]]; then
  echo "$0: file not found: $FILE" >&2
  exit 1
fi

# The arm label lives in the data: the backend column is the same on every row.
ARM_LABEL="$(awk -F ',' 'NF == 9 && $5 != "backend" { print $5; exit }' "$FILE")"
ARM_LABEL="${ARM_LABEL:-unknown}"

awk -F ',' -v arm="$ARM_LABEL" '
/^Running / { next }

# Registry counters arrive as trailer lines, not as data rows.
/^# registry_tx_bytes_delta:/ {
    v = $0
    sub(/^# registry_tx_bytes_delta:[[:space:]]*/, "", v)
    gsub(/[[:space:]]+/, "", v)
    if (v ~ /^[0-9]+$/) tx = v
    next
}
/^#/ { next }

{
    line = $0
    sub(/\r$/, "", line)
    if (split(line, c, ",") != 9) next

    img = c[3]
    if (img == "" || img == "image") next

    duration = c[8] + 0
    failed = (c[9] + 0) != 0
    seen[img] = 1

    if (c[4] == "AGGREGATE") {
        if (!failed) wall[img] = duration
    } else {
        pulls++
        if (failed) {
            pull_failed++
        } else {
            pull_ok[img]++
            pull_sum[img] += duration
        }
    }
}

END {
    for (img in seen) {
        images++
        if (pull_ok[img] > 0) {
            sum_mean += pull_sum[img] / pull_ok[img]
            images_with_mean++
        }
        if (img in wall) {
            sum_wall += wall[img]
            images_with_wall++
        }
    }

    printf "arm\t%s\n", arm
    printf "images\t%d\n", images + 0
    printf "pulls\t%d\n", pulls + 0
    printf "failed_pulls\t%d\n", pull_failed + 0

    if (tx != "")
        printf "registry_tx_gib\t%.4f\n", tx / 1024 / 1024 / 1024
    else
        printf "registry_tx_gib\t-\n"

    printf "sum_mean_pull_sec\t%.2f\t(over %d images)\n", sum_mean / 1000, images_with_mean + 0
    printf "sum_wall_clock_sec\t%.2f\t(over %d images)\n", sum_wall / 1000, images_with_wall + 0
}
' "$FILE"
