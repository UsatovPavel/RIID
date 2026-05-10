#!/usr/bin/env python3
"""Строит dataset_<provider>_{a,b}.tsv из config/imagelist/<provider>.yaml (.test.images).

Провайдер: deploy/k8s/config/config.yaml → test_registry_provider (единственный источник).

Разбиение A/B: ключи отсортированы; первая половина → A, вторая → B (size_bytes placeholder).

Колонки: repository\\ttag\\tsize_bytes\\tsize_human.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


def split_ref(image: str) -> tuple[str, str]:
    """Split image ref into repository path and tag/reference (no registry heuristics)."""
    ref = image.strip()
    if not ref:
        raise ValueError("empty image ref")
    if "@" in ref:
        repo_main, digest = ref.rsplit("@", 1)
        return repo_main, digest
    slash = ref.rfind("/")
    colon = ref.rfind(":")
    if colon > slash:
        return ref[:colon], ref[colon + 1 :]
    return ref, "latest"


def normalize_repository(repo_main: str, provider: str) -> str:
    """dockerhub list historically drops docker.io/ prefix in repository column."""
    if provider == "dockerhub":
        for prefix in ("docker.io/", "index.docker.io/"):
            if repo_main.startswith(prefix):
                return repo_main[len(prefix) :]
    return repo_main


def yq_raw(path: Path, expr: str) -> str:
    return subprocess.check_output(["yq", "e", expr, str(path)], text=True).strip()


def load_test_images(imagelist: Path) -> dict[str, str]:
    raw = subprocess.check_output(
        ["yq", "-o=json", "e", ".test.images", str(imagelist)],
        text=True,
    ).strip()
    data = json.loads(raw or "{}")
    if not isinstance(data, dict):
        raise ValueError(f"expected .test.images map in {imagelist}")
    out: dict[str, str] = {}
    for k, v in data.items():
        if v is None or v == "null":
            continue
        out[str(k)] = str(v)
    return out


def read_test_registry_provider(config_yaml: Path) -> str:
    v = yq_raw(config_yaml, ".test_registry_provider")
    if not v or v == "null":
        raise ValueError(f"missing .test_registry_provider in {config_yaml}")
    return v.strip().lower()


def provider_imagelist_filename(provider: str) -> str:
    allowed = {"dockerhub", "selectel", "local"}
    if provider not in allowed:
        raise ValueError(
            f"test_registry_provider={provider!r} must be one of {sorted(allowed)}",
        )
    return f"{provider}.yaml"


def write_dataset_pair(
    *,
    rows: list[tuple[str, str]],
    out_a: Path,
    out_b: Path,
) -> None:
    header = "repository\ttag\tsize_bytes\tsize_human\n"
    mid = (len(rows) + 1) // 2
    part_a = rows[:mid]
    part_b = rows[mid:]

    def fmt_body(items: list[tuple[str, str]]) -> str:
        lines = []
        for repo, tag in items:
            lines.append(f"{repo}\t{tag}\t0\tn/a")
        return "".join(f"{ln}\n" for ln in lines)

    out_a.parent.mkdir(parents=True, exist_ok=True)
    out_b.parent.mkdir(parents=True, exist_ok=True)
    out_a.write_text(header + fmt_body(part_a), encoding="utf-8")
    out_b.write_text(header + fmt_body(part_b), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--k8s-root",
        type=Path,
        required=True,
        help="deploy/k8s root (contains config/config.yaml and config/imagelist/)",
    )
    args = ap.parse_args()
    k8s_root: Path = args.k8s_root.resolve()

    config_yaml = k8s_root / "config" / "config.yaml"
    if not config_yaml.is_file():
        print(f"emit_datasets_from_imagelist: missing {config_yaml}", file=sys.stderr)
        return 1

    try:
        provider = read_test_registry_provider(config_yaml)
    except ValueError as e:
        print(f"emit_datasets_from_imagelist: {e}", file=sys.stderr)
        return 1

    try:
        cat_name = provider_imagelist_filename(provider)
    except ValueError as e:
        print(f"emit_datasets_from_imagelist: {e}", file=sys.stderr)
        return 1

    imagelist = k8s_root / "config" / "imagelist" / cat_name
    if not imagelist.is_file():
        print(f"emit_datasets_from_imagelist: missing catalog {imagelist}", file=sys.stderr)
        return 1

    try:
        images = load_test_images(imagelist)
    except (subprocess.CalledProcessError, ValueError, json.JSONDecodeError) as e:
        print(f"emit_datasets_from_imagelist: {imagelist}: {e}", file=sys.stderr)
        return 1

    rows: list[tuple[str, str]] = []
    for _key in sorted(images.keys()):
        ref = images[_key]
        try:
            repo_main, tag = split_ref(ref)
        except ValueError as e:
            print(f"emit_datasets_from_imagelist: skip {_key}: {e}", file=sys.stderr)
            continue
        repo_col = normalize_repository(repo_main, provider)
        rows.append((repo_col, tag))

    out_dir = Path(__file__).resolve().parent / "output"
    out_a = out_dir / f"dataset_{provider}_a.tsv"
    out_b = out_dir / f"dataset_{provider}_b.tsv"
    write_dataset_pair(rows=rows, out_a=out_a, out_b=out_b)

    print(
        f"emit_datasets_from_imagelist: provider={provider} (from {config_yaml.name}) "
        f"images={len(rows)} -> {out_a.name}, {out_b.name}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
