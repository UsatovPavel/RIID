#!/usr/bin/env python3
"""Строит deploy/k8s/config/imagelist/{selectel,local}.yaml из dockerhub.yaml.

Дублирует структуру dockerhub для infra и test:
  • infra.dragonfly — копия из источника
  • infra.images — маппинг через mapper-common.sh
  • test.images — тот же маппинг
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path


def parse_image_ref(image: str) -> tuple[str, str, str]:
    ref = image.strip()
    if not ref:
        raise ValueError("empty image ref")
    if "@" in ref:
        main, digest = ref.rsplit("@", 1)
        suffix = "@" + digest
    else:
        main = ref
        slash = main.rfind("/")
        colon = main.rfind(":")
        if colon > slash:
            suffix = main[colon:]
            main = main[:colon]
        else:
            suffix = ":latest"

    first = main.split("/", 1)[0]
    if first == "localhost" or "." in first or ":" in first:
        if "/" in main:
            registry, repo = main.split("/", 1)
        else:
            registry, repo = main, ""
    else:
        registry = "docker.io"
        repo = main
    return registry, repo, suffix


def _yq(path: Path, expr: str) -> str:
    return subprocess.check_output(
        ["yq", "e", expr, str(path)],
        text=True,
    ).strip()


def _load_images_map(dockerhub: Path, yq_expr: str) -> dict[str, str]:
    raw = subprocess.check_output(
        ["yq", "-o=json", "e", yq_expr, str(dockerhub)],
        text=True,
    ).strip()
    data = json.loads(raw or "{}")
    if not isinstance(data, dict):
        raise ValueError(f"expected map at {yq_expr}")
    out: dict[str, str] = {}
    for k, v in data.items():
        if v is None or v == "null":
            continue
        out[str(k)] = str(v)
    return out


def _map_repo(mapper_sh: Path, provider: str, repo: str, env_file: str) -> str:
    env = os.environ.copy()
    env["MP_MAPPER"] = str(mapper_sh)
    env["MP_PROVIDER"] = provider
    env["MP_REPO"] = repo
    env["MP_ENV"] = env_file
    return subprocess.check_output(
        [
            "bash",
            "-c",
            'set -euo pipefail; source "$MP_MAPPER"; map_repo_by_provider "$MP_PROVIDER" "$MP_REPO" "$MP_ENV"',
        ],
        env=env,
        text=True,
    ).strip()


def _mapped_image(
    mapper_sh: Path,
    provider: str,
    source_reg: str,
    source_repo: str,
    suffix: str,
    env_file: str,
) -> str:
    if source_reg in ("docker.io", "index.docker.io"):
        mapped_repo = _map_repo(mapper_sh, provider, source_repo, env_file)
    else:
        mapped_repo = f"{source_reg}/{source_repo}"
    return f"{mapped_repo}{suffix}"


def _fill_mapped_images(
    mapper_sh: Path,
    source: dict[str, str],
    *,
    sel_env_ok: bool,
    env_file: str,
    section_label: str,
) -> tuple[dict[str, str], dict[str, str]]:
    local_images: dict[str, str] = {}
    sel_images: dict[str, str] = {}
    for component_key in sorted(source.keys()):
        source_img = source[component_key]
        src_reg, src_repo, sfx = parse_image_ref(str(source_img))
        local_images[component_key] = _mapped_image(
            mapper_sh, "local", src_reg, src_repo, sfx, ""
        )
        if sel_env_ok:
            try:
                sel_images[component_key] = _mapped_image(
                    mapper_sh, "selectel", src_reg, src_repo, sfx, env_file
                )
            except subprocess.CalledProcessError as e:
                print(
                    f"imagelist_emit_overlays: selectel map failed for {section_label} "
                    f"key {component_key}: {e}",
                    file=sys.stderr,
                )
                sys.exit(1)
    return local_images, sel_images


def _emit_images_yaml_block(images: dict[str, str], indent: str) -> list[str]:
    """indent — пробелы перед ключами строк дерева images (например «    » для test.images)."""
    base = indent + "images:"
    if not images:
        return [base + " {}"]
    lines = [base]
    ik = indent + "  "
    for k in sorted(images):
        lines.append(f"{ik}{k}: {json.dumps(images[k])}")
    return lines


def _emit_overlay_doc(
    *,
    provider: str,
    refreshed_utc: str,
    dragonfly_block: str,
    infra_images: dict[str, str],
    test_images: dict[str, str],
) -> str:
    header_lines = [
        f"# Generated from dockerhub.yaml — do not edit by hand ({provider}).",
        "version: 1",
        f"provider: {provider}",
        f"refreshed_utc: {json.dumps(refreshed_utc)}",
        "infra:",
    ]
    parts: list[str] = header_lines
    if dragonfly_block and dragonfly_block not in ("null", "{}"):
        parts.append("  dragonfly:")
        for ln in dragonfly_block.splitlines():
            parts.append(("    " + ln) if ln else "")
    parts.extend(_emit_images_yaml_block(infra_images, indent="  "))
    parts.append("test:")
    parts.extend(_emit_images_yaml_block(test_images, indent="  "))
    return "\n".join(parts) + "\n"


def main() -> None:
    argv = sys.argv[1:]
    if len(argv) < 3 or len(argv) > 4:
        print(
            "Usage: imagelist_emit_overlays.py <dockerhub.yaml> <out-selectel.yaml> <out-local.yaml> [env-file]",
            file=sys.stderr,
        )
        sys.exit(2)

    dockerhub = Path(argv[0]).resolve()
    out_sel = Path(argv[1]).resolve()
    out_loc = Path(argv[2]).resolve()
    env_file = argv[3] if len(argv) == 4 else ""

    mapper_sh = Path(__file__).resolve().parent / "mapper-common.sh"
    if not mapper_sh.is_file():
        print(f"imagelist_emit_overlays: missing {mapper_sh}", file=sys.stderr)
        sys.exit(1)

    infra_src = _load_images_map(dockerhub, ".infra.images")
    test_src = _load_images_map(dockerhub, ".test.images // {}")

    refreshed = _yq(dockerhub, '.refreshed_utc // ""')

    try:
        dragonfly_block = subprocess.check_output(
            ["yq", "e", ".infra.dragonfly", str(dockerhub)],
            text=True,
        ).strip()
    except subprocess.CalledProcessError:
        dragonfly_block = ""

    sel_env_ok = (
        bool(env_file)
        and Path(env_file).is_file()
        and os.access(env_file, os.R_OK)
    )

    local_infra, sel_infra = _fill_mapped_images(
        mapper_sh,
        infra_src,
        sel_env_ok=sel_env_ok,
        env_file=env_file,
        section_label="infra.images",
    )
    local_test, sel_test = _fill_mapped_images(
        mapper_sh,
        test_src,
        sel_env_ok=sel_env_ok,
        env_file=env_file,
        section_label="test.images",
    )

    out_loc.parent.mkdir(parents=True, exist_ok=True)
    out_sel.parent.mkdir(parents=True, exist_ok=True)

    out_loc.write_text(
        _emit_overlay_doc(
            provider="local",
            refreshed_utc=refreshed,
            dragonfly_block=dragonfly_block,
            infra_images=local_infra,
            test_images=local_test,
        ),
        encoding="utf-8",
    )

    out_sel.write_text(
        _emit_overlay_doc(
            provider="selectel",
            refreshed_utc=refreshed,
            dragonfly_block=dragonfly_block,
            infra_images=sel_infra,
            test_images=sel_test,
        ),
        encoding="utf-8",
    )

    if not sel_env_ok:
        print(
            "imagelist_emit_overlays: no env file — selectel infra.images and test.images left empty",
            file=sys.stderr,
        )


if __name__ == "__main__":
    main()
