#!/usr/bin/env python3
"""Сужает dataset_<provider>_a.tsv до образов, перечисленных в filter TSV.

Фильтр задаётся каноническими Docker Hub repository/tag (config/imagelist/dataset_partial20.tsv),
а строки берутся из провайдерского датасета, поэтому префикс провайдера (riid/... у local)
применять не нужно — соответствие ищется через логические ключи imagelist/*.yaml.

Размеры доливаются из config/imagelist/presented_images_list_sizes.tsv: в
bootstrap/registry/init/images_list.sizes.tsv лежат только infra-образы, и у
теста две трети строк ушли бы в n/a.

Колонки на выходе те же: repository\\ttag\\tsize_bytes\\tsize_human.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

from emit_datasets_from_imagelist import (
    human_from_bytes,
    load_test_images,
    normalize_repository,
    parse_int_bytes,
    split_ref,
)

HEADER = "repository\ttag\tsize_bytes\tsize_human\n"


def read_pairs(path: Path) -> list[tuple[str, str]]:
    """Читает repository\\ttag, пропуская '#'-комментарии и строку заголовка."""
    pairs: list[tuple[str, str]] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = raw.split("\t")
        if len(parts) < 2:
            continue
        repo, tag = parts[0].strip(), parts[1].strip()
        if not repo or repo == "repository":
            continue
        pairs.append((repo, tag))
    return pairs


def load_presented_sizes(path: Path) -> dict[tuple[str, str], tuple[str, str]]:
    """presented_images_list_sizes.tsv: repository, tag, version, size_bytes, size_human, ..."""
    sizes: dict[tuple[str, str], tuple[str, str]] = {}
    if not path.is_file():
        return sizes
    for raw in path.read_text(encoding="utf-8").splitlines():
        parts = raw.split("\t")
        if len(parts) < 5 or parts[0].strip() in ("", "repository"):
            continue
        nb = parse_int_bytes(parts[3])
        if nb is None:
            continue
        human = parts[4].strip() or human_from_bytes(nb)
        sizes[(parts[0].strip(), parts[1].strip())] = (str(nb), human)
    return sizes


def dockerhub_key_by_provider_ref(
    provider: str,
    provider_images: dict[str, str],
    dock_images: dict[str, str],
) -> dict[tuple[str, str], tuple[str, str]]:
    """(repo, tag) провайдера -> (repo, tag) на Docker Hub, через общий логический ключ."""
    out: dict[tuple[str, str], tuple[str, str]] = {}
    for key, ref in provider_images.items():
        dock_ref = dock_images.get(key)
        if not dock_ref:
            continue
        try:
            p_repo, p_tag = split_ref(ref)
            d_repo, d_tag = split_ref(dock_ref)
        except ValueError:
            continue
        out[(normalize_repository(p_repo, provider), p_tag)] = (
            normalize_repository(d_repo, "dockerhub"),
            d_tag,
        )
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--k8s-root", type=Path, required=True)
    ap.add_argument("--provider", required=True)
    ap.add_argument("--source", type=Path, required=True, help="dataset_<provider>_a.tsv")
    ap.add_argument("--filter", dest="filter_tsv", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    k8s_root: Path = args.k8s_root.resolve()
    imagelist_dir = k8s_root / "config" / "imagelist"
    try:
        dock_images = load_test_images(imagelist_dir / "dockerhub.yaml")
        provider_images = load_test_images(imagelist_dir / f"{args.provider}.yaml")
    except (subprocess.CalledProcessError, ValueError, json.JSONDecodeError) as e:
        print(f"write_filtered_dataset_a: imagelist: {e}", file=sys.stderr)
        return 1

    wanted = set(read_pairs(args.filter_tsv))
    if not wanted:
        print(f"write_filtered_dataset_a: no rows in {args.filter_tsv}", file=sys.stderr)
        return 1

    to_dockerhub = dockerhub_key_by_provider_ref(args.provider, provider_images, dock_images)
    sizes = load_presented_sizes(imagelist_dir / "presented_images_list_sizes.tsv")

    kept: list[str] = []
    seen: set[tuple[str, str]] = set()
    for raw in args.source.read_text(encoding="utf-8").splitlines():
        parts = raw.split("\t")
        if len(parts) < 2 or parts[0].strip() in ("", "repository") or parts[0].startswith("#"):
            continue
        repo, tag = parts[0].strip(), parts[1].strip()
        dock = to_dockerhub.get((repo, tag))
        if dock is None or dock not in wanted:
            continue
        seen.add(dock)
        size_b = parts[2].strip() if len(parts) > 2 else "0"
        size_h = parts[3].strip() if len(parts) > 3 else "n/a"
        if size_h == "n/a" or size_b in ("", "0"):
            size_b, size_h = sizes.get(dock, (size_b or "0", size_h or "n/a"))
        kept.append(f"{repo}\t{tag}\t{size_b}\t{size_h}")

    missing = sorted(wanted - seen)
    if missing:
        for repo, tag in missing:
            print(f"write_filtered_dataset_a: not in {args.source.name}: {repo}:{tag}", file=sys.stderr)
        return 1

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(HEADER + "".join(f"{ln}\n" for ln in kept), encoding="utf-8")
    print(
        f"write_filtered_dataset_a: provider={args.provider} rows={len(kept)} -> {args.out}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
