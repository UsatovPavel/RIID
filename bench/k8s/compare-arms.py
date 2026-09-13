#!/usr/bin/env python3
"""Median, range and overlap verdict for two or more arms.

spread.py groups repeated runs by the RIID version in the filename, which is
the right axis for a version comparison and the wrong one here: the questions
this answers are "gzip against zstd" and "prefix against non-prefix", where the
version is held fixed and the ARM is what varies.

The verdict matters more than the medians. Run-to-run spread on this stand
reaches 59% on wall time within a single unchanged version, so two arms whose
ranges overlap have no measurable difference between them however far apart
their medians sit - and one run per arm can never establish a difference at all.

  compare-arms.py deploy/k8s/performance/output/dfinit-containerd*.tsv
"""
from __future__ import annotations
import os, re, sys, statistics, collections

def score(path):
    total, egress = 0.0, None
    for line in open(path, errors="replace"):
        if line.startswith("#"):
            if "registry_tx_bytes_delta" in line:
                try: egress = int(line.split("\t")[1]) / 1073741824
                except ValueError: pass
            continue
        p = line.rstrip().split(",")
        if len(p) >= 9 and p[3] == "AGGREGATE":
            try: total += float(p[7])
            except ValueError: pass
    return total / 1000, egress

def arm_of(path):
    # dfinit-containerd-zstd.agent99-20260905-2343.tsv -> dfinit-containerd-zstd
    return re.split(r"\.(agent\d+|tsv)", os.path.basename(path))[0]

def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr); return 2
    by = collections.defaultdict(list)
    for f in sys.argv[1:]:
        # The runner writes the live arm to a bare <arm>.tsv and only stamps it
        # with a timestamp once the arm is done, so a glob picks up the newest
        # run twice - which shows as a fake n and a zero-width range. Count the
        # stamped copies only; the bare one is either a duplicate or in flight.
        if not re.search(r"\.agent\d+-\d{8}-\d{4}", os.path.basename(f)):
            continue
        t, g = score(f)
        if t: by[arm_of(f)].append((t, g))
    print(f"{'арка':<32}{'n':>3}{'время: медиана':>17}{'диапазон':>19}{'egress медиана':>17}")
    band = {}
    for arm in sorted(by):
        ts = sorted(t for t, _ in by[arm])
        gs = sorted(g for _, g in by[arm] if g)
        band[arm] = (ts[0], ts[-1])
        gm = f"{statistics.median(gs):.2f} GiB" if gs else "-"
        print(f"{arm:<32}{len(ts):>3}{statistics.median(ts):>14.1f} с"
              f"{ts[0]:>11.1f}-{ts[-1]:.1f}{gm:>17}")
    arms = sorted(band)
    if len(arms) >= 2:
        print()
        for i in range(len(arms)):
            for j in range(i + 1, len(arms)):
                a, b = arms[i], arms[j]
                lo_a, hi_a = band[a]; lo_b, hi_b = band[b]
                n_a, n_b = len(by[a]), len(by[b])
                if n_a < 2 or n_b < 2:
                    verdict = "НЕ УСТАНОВЛЕНО (нужен n>=2 у обеих)"
                elif hi_a >= lo_b and hi_b >= lo_a:
                    verdict = "диапазоны ПЕРЕСЕКАЮТСЯ - разница не измерима"
                else:
                    faster = a if hi_a < lo_b else b
                    verdict = f"разделены - {faster} быстрее"
                print(f"  {a} vs {b}: {verdict}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
