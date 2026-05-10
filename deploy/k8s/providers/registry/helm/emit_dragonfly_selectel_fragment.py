#!/usr/bin/env python3
"""Emit Helm values fragment for Dragonfly on Selectel (mirror layout with .../worker/...).

Reads resolved refs from deploy/k8s/config/imagelist/selectel.yaml (.infra.images).
Tags and repositories follow the same layout as the former render-selectel-dragonfly-images.sh.

Optional env (same semantics as mapper / deploy/k8s/config/.env):
  REGISTRY_SELECTEL_ID — UUID или полный префикс host/path; один сегмент → REGISTRY_LOGIN_HOST (default cr.selcloud.ru)
  REGISTRY_LOGIN_HOST — used when REGISTRY_SELECTEL_ID is a single segment (default cr.selcloud.ru)
  DRAGONFLY_OSS_IMAGE_TAG — overrides manager, scheduler, client, seedClient tags
  DRAGONFLY_SCHEDULER_IMAGE_TAG / DRAGONFLY_CLIENT_IMAGE_TAG
  DRAGONFLY_BUSYBOX_TAG or DRAGONFLY_BUSYBOX_IMAGE_TAG — init busybox tag
  DRAGONFLY_MYSQL_IMAGE_TAG (default 8.4.3-debian-12-r0)
  DRAGONFLY_REDIS_IMAGE_TAG (default 7.2.5-debian-12-r0)

Usage: emit_dragonfly_selectel_fragment.py <path/to/selectel.yaml>
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


def tag_from_suffix(suffix: str) -> str:
    if suffix.startswith("@"):
        return suffix[1:]
    if suffix.startswith(":"):
        return suffix[1:]
    return suffix


def load_infra_images(imagelist: Path) -> dict[str, str]:
    raw = subprocess.check_output(
        ["yq", "-o=json", "e", ".infra.images", str(imagelist)],
        text=True,
    ).strip()
    data = json.loads(raw or "{}")
    if not isinstance(data, dict):
        raise ValueError("expected .infra.images map")
    out: dict[str, str] = {}
    for k, v in data.items():
        if v is None or v == "null":
            continue
        out[str(k)] = str(v)
    return out


def resolve_registry_wp(manager_repo: str) -> tuple[str, str]:
    """Returns (helm image.registry host, worker prefix for repository paths)."""
    reg = os.environ.get("REGISTRY_SELECTEL_ID", "").strip()
    if reg:
        reg = reg.rstrip("/")
        if "/" in reg:
            img_registry, reg_path = reg.split("/", 1)
        else:
            img_registry = os.environ.get("REGISTRY_LOGIN_HOST", "cr.selcloud.ru")
            reg_path = reg
        wp = f"{reg_path.rstrip('/')}/worker"
        return img_registry, wp

    pid = manager_repo.split("/", 1)[0]
    img_registry = os.environ.get("REGISTRY_LOGIN_HOST", "cr.selcloud.ru")
    return img_registry, f"{pid}/worker"


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2

    imagelist = Path(sys.argv[1])
    if not imagelist.is_file():
        print(f"emit_dragonfly_selectel_fragment: not found: {imagelist}", file=sys.stderr)
        return 1

    images = load_infra_images(imagelist)
    required = (
        "dragonfly.manager",
        "dragonfly.scheduler",
        "dragonfly.client",
        "dragonfly.seed_client",
        "storage.busybox",
    )
    missing = [k for k in required if k not in images]
    if missing:
        print(
            "emit_dragonfly_selectel_fragment: missing keys in .infra.images: "
            + ", ".join(missing),
            file=sys.stderr,
        )
        return 1

    _, mgr_repo, mgr_sfx = parse_image_ref(images["dragonfly.manager"])
    _, _, sch_sfx = parse_image_ref(images["dragonfly.scheduler"])
    _, _, cli_sfx = parse_image_ref(images["dragonfly.client"])
    _, _, bb_sfx = parse_image_ref(images["storage.busybox"])

    tag_mgr = tag_from_suffix(mgr_sfx)
    tag_sch = tag_from_suffix(sch_sfx)
    tag_cli = tag_from_suffix(cli_sfx)
    tag_bb = tag_from_suffix(bb_sfx)

    oss = os.environ.get("DRAGONFLY_OSS_IMAGE_TAG", "").strip()
    if oss:
        tag_mgr = tag_sch = tag_cli = oss
    if os.environ.get("DRAGONFLY_SCHEDULER_IMAGE_TAG"):
        tag_sch = os.environ["DRAGONFLY_SCHEDULER_IMAGE_TAG"].strip()
    if os.environ.get("DRAGONFLY_CLIENT_IMAGE_TAG"):
        tag_cli = os.environ["DRAGONFLY_CLIENT_IMAGE_TAG"].strip()
    bb_env = os.environ.get("DRAGONFLY_BUSYBOX_TAG") or os.environ.get(
        "DRAGONFLY_BUSYBOX_IMAGE_TAG",
        "",
    )
    if bb_env.strip():
        tag_bb = bb_env.strip()

    tag_mysql = os.environ.get("DRAGONFLY_MYSQL_IMAGE_TAG", "8.4.3-debian-12-r0").strip()
    tag_redis = os.environ.get("DRAGONFLY_REDIS_IMAGE_TAG", "7.2.5-debian-12-r0").strip()

    img_registry, wp = resolve_registry_wp(mgr_repo)

    print(
        f"""manager:
  image:
    registry: {img_registry}
    repository: {wp}/dragonflyoss/manager
    tag: {tag_mgr}
  initContainer:
    image:
      registry: {img_registry}
      repository: {wp}/library/busybox
      tag: {tag_bb}
scheduler:
  image:
    registry: {img_registry}
    repository: {wp}/dragonflyoss/scheduler
    tag: {tag_sch}
  initContainer:
    image:
      registry: {img_registry}
      repository: {wp}/library/busybox
      tag: {tag_bb}
seedClient:
  image:
    registry: {img_registry}
    repository: {wp}/dragonflyoss/client
    tag: {tag_cli}
  initContainer:
    image:
      registry: {img_registry}
      repository: {wp}/library/busybox
      tag: {tag_bb}
client:
  image:
    registry: {img_registry}
    repository: {wp}/dragonflyoss/client
    tag: {tag_cli}
  initContainer:
    image:
      registry: {img_registry}
      repository: {wp}/library/busybox
      tag: {tag_bb}
mysql:
  image:
    registry: {img_registry}
    repository: {wp}/bitnamilegacy/mysql
    tag: {tag_mysql}
redis:
  image:
    registry: {img_registry}
    repository: {wp}/bitnamilegacy/redis
    tag: {tag_redis}
"""
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
