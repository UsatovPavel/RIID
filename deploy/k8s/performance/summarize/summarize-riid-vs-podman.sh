#!/usr/bin/env bash
# Агрегат runner-выгрузок (CSV: scenario,mode,image,pod,backend,...,duration_ms,exit_code).
# Для каждого image: N pulls, success rate, среднее время по успешным (T_riid_ms / T_podman_ms),
# ratio = mean(riid) / mean(podman), если есть успехи по обеим сторонам.
#
# Игнорируются строки «Running scenario=» и любые строки без 9 полей после split по запятой.
# Нужен GNU awk (asorti).
#
# Usage:
#   summarize-riid-vs-podman.sh <riid_runner.csv> <podman_runner.csv>
#
# Stdout: TSV (табуляция между колонками); строки «# …» — агрегаты по множеству образов.

set -euo pipefail

if [[ "$#" -ne 2 ]]; then
  echo "Usage: $0 <riid_runner.csv> <podman_runner.csv>" >&2
  exit 2
fi
RIID_FILE="$1"
PODMAN_FILE="$2"
for f in "$RIID_FILE" "$PODMAN_FILE"; do
  if [[ ! -f "$f" ]]; then
    echo "$0: file not found: $f" >&2
    exit 1
  fi
done

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

riid_tx_delta="$(extract_registry_tx_delta "$RIID_FILE" || true)"
podman_tx_delta="$(extract_registry_tx_delta "$PODMAN_FILE" || true)"

GNU_AWK=("gawk")
if command -v gawk >/dev/null 2>&1; then :; else GNU_AWK=("awk"); fi

"${GNU_AWK[@]}" 'BEGIN {
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
    if (c[5] != "riid") next
    img = c[3]
    pod = c[4]
    if (img == "") next
    dr = c[8] + 0
    ex = c[9] + 0
    if (pod == "AGGREGATE") {
        riid_has_agg[img] = 1
        riid_agg_n[img]++
        if (ex == 0) {
            riid_agg_ok[img]++
            riid_agg_sum[img] += dr
        }
    } else {
        riid_n[img]++
        if (ex == 0) {
            riid_ok[img]++
            riid_sum[img] += dr
        }
    }
    next
}

{
    if (c[5] != "podman") next
    img = c[3]
    pod = c[4]
    if (img == "") next
    dr = c[8] + 0
    ex = c[9] + 0
    if (pod == "AGGREGATE") {
        pod_has_agg[img] = 1
        pod_agg_n[img]++
        if (ex == 0) {
            pod_agg_ok[img]++
            pod_agg_sum[img] += dr
        }
    } else {
        pod_n[img]++
        if (ex == 0) {
            pod_ok[img]++
            pod_sum[img] += dr
        }
    }
}

END {
    print "image", "N_riid", "success_rate_riid_pct", "T_riid_mean_ms",
          "N_podman", "success_rate_podman_pct", "T_podman_mean_ms", "ratio_riid_over_podman"

    for (k in riid_n)
        union[k] = 1
    for (k in riid_agg_n)
        union[k] = 1
    for (k in pod_n)
        union[k] = 1
    for (k in pod_agg_n)
        union[k] = 1

    nuni = asorti(union, keys)
    imgs_with_ratio = 0
    sum_ratio = 0

    for (i = 1; i <= nuni; i++) {
        img = keys[i]

        # Always use individual pod mean, ignore AGGREGATE
        n1 = riid_n[img] + 0
        o1 = riid_ok[img] + 0
        s1 = riid_sum[img] + 0

        n2 = pod_n[img] + 0
        o2 = pod_ok[img] + 0
        s2 = pod_sum[img] + 0

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
' "$RIID_FILE" "$PODMAN_FILE"

if [[ "$riid_tx_delta" =~ ^[0-9]+$ ]]; then
  riid_tx_gib="$(format_gib "$riid_tx_delta")"
else
  riid_tx_delta="-"
  riid_tx_gib="-"
fi

if [[ "$podman_tx_delta" =~ ^[0-9]+$ ]]; then
  podman_tx_gib="$(format_gib "$podman_tx_delta")"
else
  podman_tx_delta="-"
  podman_tx_gib="-"
fi

tx_ratio="-"
tx_reduction_pct="-"
if [[ "$riid_tx_delta" =~ ^[0-9]+$ && "$podman_tx_delta" =~ ^[0-9]+$ && "$podman_tx_delta" -gt 0 ]]; then
  tx_ratio="$(awk -v a="$riid_tx_delta" -v b="$podman_tx_delta" 'BEGIN { printf "%.4f", a / b }')"
  tx_reduction_pct="$(awk -v a="$riid_tx_delta" -v b="$podman_tx_delta" 'BEGIN { printf "%.2f", (1 - a / b) * 100 }')"
fi

printf '# registry_tx_gib_riid:\t%s\n' "$riid_tx_gib"
printf '# registry_tx_gib_podman:\t%s\n' "$podman_tx_gib"
printf '# registry_tx_ratio_riid_over_podman:\t%s\n' "$tx_ratio"
printf '# registry_tx_reduction_pct_vs_podman:\t%s\n' "$tx_reduction_pct"
