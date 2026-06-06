#!/usr/bin/env python3
"""Строит dataset_{dockerhub,selectel,local}_{a,b}.tsv из config/imagelist/*.yaml (.test.images).

Размеры (size_bytes, size_human) совпадают для всех провайдеров: ключ — логическое имя
образа; канонический repository/tag берётся из imagelist/dockerhub.yaml и ищется в
bootstrap/registry/init/images_list.sizes.tsv. У selectel/local в колонке repository
другие префиксы, но байты те же, что у зеркала на Docker Hub.

Разбиение A/B: dataset_b — образы строго больше MIN_SIZE_GBS GiB (порог из GiB через *1024³);
остальное и все n/a — dataset_a.

Колонки: repository\\ttag\\tsize_bytes\\tsize_human.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


PROVIDERS: tuple[str, ...] = ("dockerhub", "selectel", "local")
MIN_SIZE_GBS = 1
DATASET_B_MIN_SIZE_BYTES = MIN_SIZE_GBS * 1024 * 1024 * 1024


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


def provider_imagelist_filename(provider: str) -> str:
    if provider not in PROVIDERS:
        raise ValueError(f"provider={provider!r} must be one of {PROVIDERS}")
    return f"{provider}.yaml"


def parse_int_bytes(s: str) -> int | None:
    s = s.strip()
    if not s:
        return None
    try:
        return int(s)
    except ValueError:
        return None


def human_from_bytes(n: int) -> str:
    """Binary IEC labels like existing datasets (2.4 KiB, 310.2 MiB)."""
    if n < 0:
        return "n/a"
    for scale, label in (
        (1 << 40, "TiB"),
        (1 << 30, "GiB"),
        (1 << 20, "MiB"),
        (1 << 10, "KiB"),
    ):
        if n >= scale:
            return f"{n / scale:.1f} {label}"
    return f"{n} B"


def load_sizes_tsv(path: Path) -> dict[tuple[str, str], tuple[str, str]]:
    """Map (repository, tag) dockerhub-normalized → (size_bytes, size_human)."""
    if not path.is_file():
        return {}
    data: dict[tuple[str, str], tuple[str, str]] = {}
    for lineno, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if lineno == 1 and line.startswith("repository\t"):
            continue
        parts = raw_line.split("\t")
        if len(parts) < 3:
            continue
        repo, tag = parts[0].strip(), parts[1].strip()
        sb_raw = parts[2].strip() if len(parts) > 2 else ""
        sh_raw = parts[3].strip() if len(parts) > 3 else ""
        nb = parse_int_bytes(sb_raw)
        if nb is None:
            size_b, size_h = "0", ("n/a" if not sh_raw else sh_raw)
        else:
            size_b = str(nb)
            size_h = sh_raw if sh_raw else human_from_bytes(nb)
        data[(repo, tag)] = (size_b, size_h)
    return data


def dockerhub_size_key(
    logical_key: str,
    dock_images: dict[str, str],
) -> tuple[str, str] | None:
    ref = dock_images.get(logical_key)
    if not ref:
        return None
    try:
        repo_main, tag = split_ref(ref)
    except ValueError:
        return None
    repo_col = normalize_repository(repo_main, "dockerhub")
    return repo_col, tag


def build_rows_for_provider(
    *,
    provider: str,
    images: dict[str, str],
    dock_images: dict[str, str],
    sizes: dict[tuple[str, str], tuple[str, str]],
) -> list[tuple[str, str, str, str]]:
    rows: list[tuple[str, str, str, str]] = []
    for logical_key in sorted(images.keys()):
        ref = images[logical_key]
        try:
            repo_main, tag = split_ref(ref)
        except ValueError as e:
            print(f"emit_datasets_from_imagelist: skip {logical_key}: {e}", file=sys.stderr)
            continue
        repo_col = normalize_repository(repo_main, provider)
        dk = dockerhub_size_key(logical_key, dock_images)
        if dk is None:
            print(
                f"emit_datasets_from_imagelist: warning: {logical_key!r} missing in "
                "dockerhub.yaml .test.images; sizes=0/n/a",
                file=sys.stderr,
            )
            sb, sh = "0", "n/a"
        else:
            sb, sh = sizes.get(dk, ("0", "n/a"))
        rows.append((repo_col, tag, sb, sh))
    return rows


def write_dataset_pair(
    *,
    rows: list[tuple[str, str, str, str]],
    out_a: Path,
    out_b: Path,
) -> None:
    header = "repository\ttag\tsize_bytes\tsize_human\n"
    large_rows: list[tuple[str, str, str, str, int]] = []
    small_rows: list[tuple[str, str, str, str, int]] = []
    na_rows: list[tuple[str, str, str, str]] = []
    for repo, tag, sb, sh in rows:
        if sh == "n/a":
            na_rows.append((repo, tag, sb, sh))
            continue
        try:
            n = int(sb)
        except ValueError:
            na_rows.append((repo, tag, sb, sh))
            continue
        if n > DATASET_B_MIN_SIZE_BYTES:
            large_rows.append((repo, tag, sb, sh, n))
        else:
            small_rows.append((repo, tag, sb, sh, n))

    # Stable deterministic order:
    # - unknown sizes (n/a) and small known images stay in dataset_a
    # - only images strictly bigger than MIN_SIZE_GBS GiB go to dataset_b
    small_rows.sort(key=lambda row: (row[4], row[0], row[1]))
    large_rows.sort(key=lambda row: (row[4], row[0], row[1]))
    na_rows.sort(key=lambda row: (row[0], row[1]))

    part_a = na_rows + [(repo, tag, sb, sh) for repo, tag, sb, sh, _ in small_rows]
    part_b = [(repo, tag, sb, sh) for repo, tag, sb, sh, _ in large_rows]

    def fmt_body(items: list[tuple[str, str, str, str]]) -> str:
        lines = []
        for repo, tag, sb, sh in items:
            lines.append(f"{repo}\t{tag}\t{sb}\t{sh}")
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
        help="deploy/k8s root (config/imagelist/, bootstrap/registry/init/)",
    )
    ap.add_argument(
        "--sizes-tsv",
        type=Path,
        default=None,
        help="override path to images_list.sizes.tsv (default: bootstrap/registry/init/)",
    )
    args = ap.parse_args()
    k8s_root: Path = args.k8s_root.resolve()
    imagelist_dir = k8s_root / "config" / "imagelist"
    dockerhub_yaml = imagelist_dir / "dockerhub.yaml"
    if not dockerhub_yaml.is_file():
        print(f"emit_datasets_from_imagelist: missing {dockerhub_yaml}", file=sys.stderr)
        return 1

    sizes_path = (
        args.sizes_tsv.resolve()
        if args.sizes_tsv is not None
        else k8s_root / "bootstrap" / "registry" / "init" / "images_list.sizes.tsv"
    )
    sizes = load_sizes_tsv(sizes_path)
    if not sizes:
        print(
            f"emit_datasets_from_imagelist: warning: no sizes loaded from {sizes_path}, "
            "using 0/n/a",
            file=sys.stderr,
        )

    try:
        dock_images = load_test_images(dockerhub_yaml)
    except (subprocess.CalledProcessError, ValueError, json.JSONDecodeError) as e:
        print(f"emit_datasets_from_imagelist: {dockerhub_yaml}: {e}", file=sys.stderr)
        return 1

    out_dir = Path(__file__).resolve().parent / "output"

    for provider in PROVIDERS:
        cat = imagelist_dir / provider_imagelist_filename(provider)
        if not cat.is_file():
            print(f"emit_datasets_from_imagelist: missing catalog {cat}", file=sys.stderr)
            return 1
        try:
            images = load_test_images(cat)
        except (subprocess.CalledProcessError, ValueError, json.JSONDecodeError) as e:
            print(f"emit_datasets_from_imagelist: {cat}: {e}", file=sys.stderr)
            return 1

        rows = build_rows_for_provider(
            provider=provider,
            images=images,
            dock_images=dock_images,
            sizes=sizes,
        )
        out_a = out_dir / f"dataset_{provider}_a.tsv"
        out_b = out_dir / f"dataset_{provider}_b.tsv"
        write_dataset_pair(rows=rows, out_a=out_a, out_b=out_b)

        missing_sz = sum(1 for r in rows if r[2] == "0" and r[3] == "n/a")
        print(
            f"emit_datasets_from_imagelist: provider={provider} rows={len(rows)} "
            f"missing_size={missing_sz} -> {out_a.name}, {out_b.name}",
            file=sys.stderr,
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
