#!/usr/bin/env python3
"""Compact ctr's per-blob progress into one line per blob.

`ctr images pull` redraws its whole progress table on every refresh, so the
captured stdout repeats each blob's state hundreds of times - a megabyte per
40 seconds, almost all of it duplicates. This keeps what is actually
informative: for each blob, the image it belongs to and the sequence of states
it passed through, in order, with the first time each state was seen.

  ctr-blobs-compact.py <ctr-blobs.log> [> ctr-blobs-summary.txt]
"""
from __future__ import annotations

import re
import sys

ANSI = re.compile(r"\x1b\[[0-9;]*m")
HEADER = re.compile(r"^===== (\S+) ")
# "└──layer (efecee6d8e1b)   extracted   |++++|"  /  "   └──config (8012…) waiting"
BLOB = re.compile(r"(manifest|config|layer) \(([0-9a-f]{12})\)\s+([a-z ]+?)\s*(?:\||$)")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    image = "?"
    # (image, kind, digest) -> [states in first-seen order]
    seen: dict[tuple[str, str, str], list[str]] = {}
    order: list[tuple[str, str, str]] = []
    with open(sys.argv[1], errors="replace") as handle:
        for raw in handle:
            line = ANSI.sub("", raw).rstrip()
            head = HEADER.match(line)
            if head:
                image = head.group(1)
                continue
            m = BLOB.search(line)
            if not m:
                continue
            kind, digest, state = m.group(1), m.group(2), m.group(3).strip()
            key = (image, kind, digest)
            if key not in seen:
                seen[key] = []
                order.append(key)
            # The progress table is redrawn continuously, so a state sequence is
            # mostly redraw noise. What matters per blob is the SET of states it
            # was ever in: "downloading" means it came over the wire, its absence
            # means the local store already had it.
            if state not in seen[key]:
                seen[key].append(state)

    by_image: dict[str, int] = {}
    for (img, kind, digest) in order:
        by_image[img] = by_image.get(img, 0) + 1
    print(f"# {len(order)} blob records across {len(by_image)} images")
    current = None
    for key in order:
        img, kind, digest = key
        if img != current:
            current = img
            print(f"\n{img}  ({by_image[img]} blobs)")
        states = seen[key]
        verdict = "fetched" if "downloading" in states else (
            "reused" if "already exists" in states else "-")
        print(f"  {kind:8} {digest}  {verdict:8} [{', '.join(sorted(states))}]")
    return 0


if __name__ == "__main__":
    sys.exit(main())
