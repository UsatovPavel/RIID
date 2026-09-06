#!/usr/bin/env python3
"""Median and range per version across repeated runs of one arm.

Two runs of the same image on this stand came out 42% apart in time and 49% in
egress, so a mean would hide exactly what matters. Report the median and the
full range instead, and say plainly when two versions' ranges overlap - because
then the difference between them is not measurable here, however tidy the
averages look.

  spread.py deploy/k8s/performance/output/riid-podman.agent99-*-v0.4.1*-r*.tsv
"""
from __future__ import annotations
import re, sys, statistics, collections

def score(path):
    total, egress = 0.0, None
    for line in open(path, errors="replace"):
        if line.startswith("#"):
            if "registry_tx_bytes_delta" in line:
                egress = int(line.split("\t")[1]) / 1073741824
            continue
        p = line.rstrip().split(",")
        if len(p) >= 9 and p[3] == "AGGREGATE":
            try: total += float(p[7])
            except ValueError: pass
    return total / 1000, egress

def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr); return 2
    by = collections.defaultdict(list)
    for f in sys.argv[1:]:
        m = re.search(r"(v\d+\.\d+\.\d+)", f)
        if not m: continue
        t, g = score(f)
        if t and g: by[m.group(1)].append((t, g))
    print(f"{'версия':10}{'n':>3}{'время: медиана':>16}{'диапазон':>20}{'egress: медиана':>18}{'диапазон':>20}")
    spans = {}
    for ver in sorted(by):
        ts = sorted(t for t, _ in by[ver]); gs = sorted(g for _, g in by[ver])
        spans[ver] = (ts[0], ts[-1])
        print(f"{ver:10}{len(ts):>3}{statistics.median(ts):>13.1f} с"
              f"{f'{ts[0]:.0f}-{ts[-1]:.0f} ({(ts[-1]/ts[0]-1)*100:.0f}%)':>20}"
              f"{statistics.median(gs):>15.2f} Gi"
              f"{f'{gs[0]:.2f}-{gs[-1]:.2f} ({(gs[-1]/gs[0]-1)*100:.0f}%)':>20}")
    vers = sorted(spans)
    if len(vers) == 2:
        (a1, a2), (b1, b2) = spans[vers[0]], spans[vers[1]]
        overlap = not (a2 < b1 or b2 < a1)
        print(f"\n  диапазоны {'перекрываются' if overlap else 'НЕ перекрываются'}: "
              + ("разница между версиями на этом стенде не измерима"
                 if overlap else "различие выходит за пределы разброса"))
    return 0

if __name__ == "__main__":
    sys.exit(main())
