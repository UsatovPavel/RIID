#!/usr/bin/env python3
"""Builds performance/input/dataset_a.tsv from filter.tsv + a provider dataset.

filter.tsv holds the curated subset of the full catalog (canonical Docker Hub
repository/tag, one row each) that the AGENT-74/AGENT-99/AGENT-103 bench
matrix actually pulls. --source is one of
providers/registry/image/dataset/output/dataset_<provider>_a.tsv, produced by
emit_datasets_from_imagelist.py; it has the full catalog with the
provider-specific repository prefix and known sizes. This script looks up
each filter.tsv row's provider-prefixed repository in --source and writes
the matched rows, in filter.tsv's order, to --out.

Columns (both filter.tsv and the output): repository\ttag[\tsize_bytes\tsize_human].
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

PROVIDER_REPO_PREFIX = {"dockerhub": "", "selectel": "riid", "local": "riid"}


def provider_repo(canonical_repo: str, provider: str) -> str:
    """Maps a filter.tsv canonical repo to the --source file's repository column.

    Matches the convention already baked into dataset_selectel_a.tsv /
    dataset_local_a.tsv: drop a leading "library/" (Docker Hub's implicit
    namespace), then prepend the provider's registry namespace, if any.
    """
    repo = canonical_repo
    if repo.startswith("library/"):
        repo = repo[len("library/") :]
    prefix = PROVIDER_REPO_PREFIX.get(provider, "")
    return f"{prefix}/{repo}" if prefix else repo


def read_tsv_rows(path: Path) -> list[tuple[str, ...]]:
    rows: list[tuple[str, ...]] = []
    for lineno, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.rstrip("\r")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = tuple(line.split("\t"))
        if lineno == 1 or (parts and parts[0] == "repository"):
            if parts[0] == "repository":
                continue
        rows.append(parts)
    return rows


def load_source_index(path: Path) -> dict[tuple[str, str], tuple[str, str]]:
    """Maps (repository, tag) -> (size_bytes, size_human) from a provider dataset."""
    index: dict[tuple[str, str], tuple[str, str]] = {}
    for parts in read_tsv_rows(path):
        if len(parts) < 2:
            continue
        repo, tag = parts[0].strip(), parts[1].strip()
        size_bytes = parts[2].strip() if len(parts) > 2 else "0"
        size_human = parts[3].strip() if len(parts) > 3 else "n/a"
        index[(repo, tag)] = (size_bytes, size_human)
    return index


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--k8s-root", type=Path, required=True, help="deploy/k8s root (unused directly, kept for CLI parity with emit_datasets_from_imagelist.py)")
    ap.add_argument("--provider", required=True, choices=sorted(PROVIDER_REPO_PREFIX))
    ap.add_argument("--source", type=Path, required=True, help="dataset_<provider>_a.tsv to look up sizes/prefix in")
    ap.add_argument("--filter", type=Path, required=True, help="filter.tsv: canonical repository+tag rows to keep")
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    if not args.source.is_file():
        print(f"write_filtered_dataset_a: missing --source {args.source}", file=sys.stderr)
        return 1
    if not args.filter.is_file():
        print(f"write_filtered_dataset_a: missing --filter {args.filter}", file=sys.stderr)
        return 1

    source_index = load_source_index(args.source)
    filter_rows = read_tsv_rows(args.filter)
    if not filter_rows:
        print(f"write_filtered_dataset_a: {args.filter} has no data rows", file=sys.stderr)
        return 1

    out_lines = ["repository\ttag\tsize_bytes\tsize_human\n"]
    missing = 0
    for parts in filter_rows:
        if len(parts) < 2:
            continue
        canonical_repo, tag = parts[0].strip(), parts[1].strip()
        repo = provider_repo(canonical_repo, args.provider)
        key = (repo, tag)
        if key not in source_index:
            print(
                f"write_filtered_dataset_a: warning: {canonical_repo}:{tag} "
                f"(-> {repo}:{tag}) not found in {args.source}, skipping",
                file=sys.stderr,
            )
            missing += 1
            continue
        size_bytes, size_human = source_index[key]
        out_lines.append(f"{repo}\t{tag}\t{size_bytes}\t{size_human}\n")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("".join(out_lines), encoding="utf-8")
    kept = len(out_lines) - 1
    print(
        f"write_filtered_dataset_a: wrote {kept} rows to {args.out} "
        f"(filter had {len(filter_rows)}, missing {missing})",
        file=sys.stderr,
    )
    return 0 if missing == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
