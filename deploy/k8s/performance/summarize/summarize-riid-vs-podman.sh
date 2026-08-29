#!/usr/bin/env bash
# Агрегат runner-выгрузок (CSV: scenario,mode,image,pod,backend,...,duration_ms,exit_code).
# Compares ANY pair of arms of the matrix: the arm is identified by the file, not
# by a literal in the backend column — that column now holds a label such as
# riid-podman / dfinit-containerd, and a hardcoded list of pairs would have to
# grow with every new engine.
# Per image: N pulls, success rate, mean time over the successful ones, ratio A/B.
#
# Игнорируются строки «Running scenario=» и любые строки без 9 полей после split по запятой.
# Нужен GNU awk (asorti).
#
# Usage:
#   summarize-riid-vs-podman.sh <arm_a.tsv> <arm_b.tsv>
#
# Stdout: TSV (табуляция между колонками); строки «# …» — агрегаты по множеству образов.

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

# The arm label is read from the data: the backend column is the same on every
# row of a file.
arm_label() {
  awk -F ',' 'NF == 9 && $5 != "backend" { print $5; exit }' "$1"
}

extract_registry_tx_delta() {
  local f="$1"
  awk -F '\t' '
    /^# registry_tx_bytes_delta:/ {
      v = $2
      gsub(/[[:space:]]+/, "", v)
      last = v
    }
    END {
      if (last ~ /^[0-9]+$/) print last
    }
  ' "$f"
}

format_gib() {
  local bytes="$1"
  awk -v b="$bytes" 'BEGIN { printf "%.4f", b / 1024 / 1024 / 1024 }'
}

LABEL_A="$(arm_label "$A_FILE")"; LABEL_A="${LABEL_A:-a}"
LABEL_B="$(arm_label "$B_FILE")"; LABEL_B="${LABEL_B:-b}"

a_tx_delta="$(extract_registry_tx_delta "$A_FILE" || true)"
b_tx_delta="$(extract_registry_tx_delta "$B_FILE" || true)"

GNU_AWK=("gawk")
if command -v gawk >/dev/null 2>&1; then :; else GNU_AWK=("awk"); fi

"${GNU_AWK[@]}" -v la="$LABEL_A" -v lb="$LABEL_B" 'BEGIN {
    FS = ","
    OFS = "\t"
}

function rate_pct(s, tot,      t) {
    t = tot + 0
    if (t <= 0) return ""
    return int(1000 * ((s + 0) / t) + 0.5) / 10
}

/^Running / { next }
{
    line = $0
    sub(/\r$/, "", line)
    n = split(line, c, ",")
    if (n != 9) next
}

NR == FNR {
    img = c[3]
    pod = c[4]
    if (img == "") next
    dr = c[8] + 0
    ex = c[9] + 0
    if (pod == "AGGREGATE") {
        a_has_agg[img] = 1
        a_agg_n[img]++
        if (ex == 0) {
            a_agg_ok[img]++
            a_agg_sum[img] += dr
        }
    } else {
        a_n[img]++
        if (ex == 0) {
            a_ok[img]++
            a_sum[img] += dr
        }
    }
    next
}

{
    img = c[3]
    pod = c[4]
    if (img == "") next
    dr = c[8] + 0
    ex = c[9] + 0
    if (pod == "AGGREGATE") {
        b_has_agg[img] = 1
        b_agg_n[img]++
        if (ex == 0) {
            b_agg_ok[img]++
            b_agg_sum[img] += dr
        }
    } else {
        b_n[img]++
        if (ex == 0) {
            b_ok[img]++
            b_sum[img] += dr
        }
    }
}

END {
    print "image", "N_" la, "success_rate_" la "_pct", "T_" la "_mean_ms",
          "N_" lb, "success_rate_" lb "_pct", "T_" lb "_mean_ms", "ratio_" la "_over_" lb

    for (k in a_n)
        union[k] = 1
    for (k in a_agg_n)
        union[k] = 1
    for (k in b_n)
        union[k] = 1
    for (k in b_agg_n)
        union[k] = 1

    nuni = asorti(union, keys)
    imgs_with_ratio = 0
    sum_ratio = 0

    for (i = 1; i <= nuni; i++) {
        img = keys[i]

        # Always use individual pod mean, ignore AGGREGATE
        n1 = a_n[img] + 0
        o1 = a_ok[img] + 0
        s1 = a_sum[img] + 0

        n2 = b_n[img] + 0
        o2 = b_ok[img] + 0
        s2 = b_sum[img] + 0

        if (o1 > 0) m1 = s1 / o1; else m1 = ""
        if (o2 > 0) m2 = s2 / o2; else m2 = ""

        ratio = "-"
        if (o1 > 0 && o2 > 0 && (m2 + 0) > 0) {
            r = m1 / m2
            ratio = sprintf("%.4f", r)
            imgs_with_ratio++
            sum_ratio += r
        }

        print img,
            n1,
            rate_pct(o1, n1),
            (m1 == "" ? "-" : sprintf("%.2f", m1)),
            n2,
            rate_pct(o2, n2),
            (m2 == "" ? "-" : sprintf("%.2f", m2)),
            ratio
    }

    print "# images_with_both_backend_means_and_ratio_defined:", imgs_with_ratio
    if (imgs_with_ratio > 0)
        printf "# avg_ratio_simple_mean_over_images_with_ratio: %.4f\n", sum_ratio / imgs_with_ratio
}
' "$A_FILE" "$B_FILE"

if [[ "$a_tx_delta" =~ ^[0-9]+$ ]]; then
  a_tx_gib="$(format_gib "$a_tx_delta")"
else
  a_tx_delta="-"
  a_tx_gib="-"
fi

if [[ "$b_tx_delta" =~ ^[0-9]+$ ]]; then
  b_tx_gib="$(format_gib "$b_tx_delta")"
else
  b_tx_delta="-"
  b_tx_gib="-"
fi

tx_ratio="-"
tx_reduction_pct="-"
if [[ "$a_tx_delta" =~ ^[0-9]+$ && "$b_tx_delta" =~ ^[0-9]+$ && "$b_tx_delta" -gt 0 ]]; then
  tx_ratio="$(awk -v a="$a_tx_delta" -v b="$b_tx_delta" 'BEGIN { printf "%.4f", a / b }')"
  tx_reduction_pct="$(awk -v a="$a_tx_delta" -v b="$b_tx_delta" 'BEGIN { printf "%.2f", (1 - a / b) * 100 }')"
fi

printf '# registry_tx_gib_%s:\t%s\n' "$LABEL_A" "$a_tx_gib"
printf '# registry_tx_gib_%s:\t%s\n' "$LABEL_B" "$b_tx_gib"
printf '# registry_tx_ratio_%s_over_%s:\t%s\n' "$LABEL_A" "$LABEL_B" "$tx_ratio"
printf '# registry_tx_reduction_pct_vs_%s:\t%s\n' "$LABEL_B" "$tx_reduction_pct"
