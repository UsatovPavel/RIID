#!/usr/bin/env python3
"""Каталог performance/smoke: образы по logical_key из imagelist (config как источник правды).

API:
  catalog_test_pull_bundle(k8s_root, logical_key) — один ключ.
  all_catalog_test_pull_bundles(k8s_root) — все тестовые ключи (sorted).
  resolved_test_pull_refs(k8s_root) — плоский упорядоченный список всех отрезолвленных ref из каталога.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class CatalogTestPullBundle:
    """Образ(ы) для скачивания под logical_key при текущем test_registry_provider."""

    logical_key: str
    test_registry_provider: str
    pull_refs: tuple[str, ...]
    repository: str
    reference: str
    dockerhub_pull_ref: str | None


def split_ref(image: str) -> tuple[str, str]:
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


def normalize_dockerhub_repository(repo_main: str) -> str:
    for prefix in ("docker.io/", "index.docker.io/"):
        if repo_main.startswith(prefix):
            return repo_main[len(prefix) :]
    return repo_main


def repository_for_riid_pull(provider: str, catalog_ref: str) -> str:
    repo_main, _tag = split_ref(catalog_ref)
    if provider == "dockerhub":
        return normalize_dockerhub_repository(repo_main)
    return repo_main


def repo_has_explicit_registry(repo_main: str) -> bool:
    """Первый сегмент пути repo трактуется как registry host при наличии . или :."""
    first = repo_main.split("/", 1)[0]
    return bool(first == "localhost" or "." in first or ":" in first)


def canonical_pull_ref(catalog_ref: str, pull_host: str | None) -> str:
    """Полная ссылка для podman/docker pull (дописывает pull_host для коротких путей)."""
    cat = catalog_ref.strip()
    rm, suf = split_ref(cat)
    if repo_has_explicit_registry(rm):
        return cat
    ph = (pull_host or "").strip().rstrip("/")
    if not ph:
        return cat
    if "@" in cat:
        return f"{ph}/{rm}@{suf}"
    return f"{ph}/{rm}:{suf}"


def resolve_pull_host_prefix(k8s_root: Path) -> str | None:
    """Из ENV или необязательного поля config (не Selectel-only .env)."""
    env_h = os.environ.get("TEST_REGISTRY_PULL_HOST", "").strip()
    if env_h:
        return env_h
    cfg = k8s_root / "config" / "config.yaml"
    if not cfg.is_file():
        return None
    raw = yq_raw(cfg, ".test_registry_pull_host // \"\"").strip()
    return raw or None


def registry_host_hint_for_podman(k8s_root: Path) -> str | None:
    """Хост для podman при сборке host/repo:tag (когда образ в каталоге без явного registry)."""
    refs = resolved_test_pull_refs(k8s_root)
    if not refs:
        return None
    rm, _tg = split_ref(refs[0])
    if repo_has_explicit_registry(rm):
        return rm.split("/", 1)[0]
    return resolve_pull_host_prefix(k8s_root)


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


def find_logical_key_for_hub_repo(dh_images: dict[str, str], hub_repo: str) -> str | None:
    want = hub_repo.strip()
    if not want:
        return None
    for logical_key in sorted(dh_images.keys()):
        ref = dh_images[logical_key]
        try:
            dh_main, _ = split_ref(ref)
        except ValueError:
            continue
        if normalize_dockerhub_repository(dh_main) == want:
            return logical_key
    return None


def _bundle_row(
    *,
    lk: str,
    provider: str,
    pref: str,
    dockerhub_pull_ref: str | None,
) -> CatalogTestPullBundle:
    pref_st = pref.strip()
    ref_suffix = split_ref(pref_st)[1]
    return CatalogTestPullBundle(
        logical_key=lk,
        test_registry_provider=provider,
        pull_refs=(pref_st,),
        repository=repository_for_riid_pull(provider, pref_st),
        reference=ref_suffix,
        dockerhub_pull_ref=dockerhub_pull_ref.strip() if dockerhub_pull_ref else None,
    )


def all_catalog_test_pull_bundles(k8s_root: Path) -> tuple[CatalogTestPullBundle, ...]:
    """Все записи .test.images активного провайдера (ключи по алфавиту)."""
    k8s_root = k8s_root.resolve()
    config_yaml = k8s_root / "config" / "config.yaml"
    dh_yaml = k8s_root / "config" / "imagelist" / "dockerhub.yaml"

    if not config_yaml.is_file():
        raise FileNotFoundError(str(config_yaml))

    provider = read_test_registry_provider(config_yaml)
    allowed = {"dockerhub", "selectel", "local"}
    if provider not in allowed:
        raise ValueError(f"unsupported test_registry_provider={provider!r}")

    cat = k8s_root / "config" / "imagelist" / f"{provider}.yaml"
    if not cat.is_file():
        raise FileNotFoundError(str(cat))

    pv_images = load_test_images(cat)
    dh_images: dict[str, str] = {}
    if dh_yaml.is_file():
        try:
            dh_images = load_test_images(dh_yaml)
        except (subprocess.CalledProcessError, ValueError, json.JSONDecodeError):
            dh_images = {}

    bundles: list[CatalogTestPullBundle] = []
    for lk in sorted(pv_images.keys()):
        pref = pv_images[lk]
        dh_ref = dh_images.get(lk)
        bundles.append(
            _bundle_row(
                lk=lk,
                provider=provider,
                pref=pref,
                dockerhub_pull_ref=dh_ref,
            ),
        )
    return tuple(bundles)


def resolved_test_pull_refs(k8s_root: Path) -> tuple[str, ...]:
    """Плоский список всех отрезолвленных тестовых образов (полные строки из каталога провайдера)."""
    return tuple(ref for b in all_catalog_test_pull_bundles(k8s_root) for ref in b.pull_refs)


def catalog_test_pull_bundle(k8s_root: Path, logical_key: str) -> CatalogTestPullBundle:
    """Один ключ; переиспользует общий обход каталога."""
    lk = logical_key.strip()
    for b in all_catalog_test_pull_bundles(k8s_root):
        if b.logical_key == lk:
            return b
    cfg = k8s_root.resolve() / "config" / "config.yaml"
    prov = read_test_registry_provider(cfg) if cfg.is_file() else "?"
    raise KeyError(f"no .test.images[{lk!r}] in imagelist/{prov}.yaml")


def emit_catalog_load_lines(k8s_root: Path) -> list[tuple[str, str, str]]:
    """Строки для loader: canonical_pull_ref, repository (dst path), reference."""
    ph = resolve_pull_host_prefix(k8s_root)
    rows: list[tuple[str, str, str]] = []
    for b in all_catalog_test_pull_bundles(k8s_root):
        raw_ref = b.pull_refs[0]
        rows.append(
            (
                canonical_pull_ref(raw_ref, ph),
                b.repository,
                b.reference,
            ),
        )
    return rows


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--k8s-root", type=Path, required=True)

    ap.add_argument(
        "--list-all-pull-refs",
        action="store_true",
        help="Все отрезолвленные ref каталога (по строке)",
    )
    ap.add_argument(
        "--list-all-bundles-json",
        action="store_true",
        help="JSON-массив всех CatalogTestPullBundle",
    )
    ap.add_argument(
        "--emit-catalog-load-lines",
        action="store_true",
        help="TSV: pull_ref<TAB>repository<TAB>reference для load-dataset (resolver)",
    )
    ap.add_argument(
        "--pull-host-hint",
        action="store_true",
        help="Подсказка hostname для podman (FQ первого ref или test_registry_pull_host)",
    )

    g = ap.add_mutually_exclusive_group()
    g.add_argument("--logical-key")
    g.add_argument("--docker-hub-repository")

    ap.add_argument(
        "--json",
        action="store_true",
        help="С одним ключом — полный bundle JSON (иначе только repository)",
    )
    args = ap.parse_args()
    k8s_root: Path = args.k8s_root.resolve()

    try:
        if args.list_all_pull_refs:
            for r in resolved_test_pull_refs(k8s_root):
                print(r)
            return 0

        if args.list_all_bundles_json:
            blobs = []
            for b in all_catalog_test_pull_bundles(k8s_root):
                d = asdict(b)
                d["pull_refs"] = list(b.pull_refs)
                blobs.append(d)
            print(json.dumps(blobs, indent=2))
            return 0

        if args.emit_catalog_load_lines:
            for pull_ref, repo, ref in emit_catalog_load_lines(k8s_root):
                print(f"{pull_ref}\t{repo}\t{ref}")
            return 0

        if args.pull_host_hint:
            h = registry_host_hint_for_podman(k8s_root)
            if h:
                print(h)
            return 0

        config_yaml = k8s_root / "config" / "config.yaml"
        dh_yaml = k8s_root / "config" / "imagelist" / "dockerhub.yaml"

        if not config_yaml.is_file():
            print(f"resolve_smoke_repository: missing {config_yaml}", file=sys.stderr)
            return 1

        if not args.logical_key and not args.docker_hub_repository:
            print(
                "resolve_smoke_repository: нужен --logical-key или --docker-hub-repository "
                "(или режим --list-*)",
                file=sys.stderr,
            )
            return 2

        logical_key: str
        if args.logical_key:
            logical_key = args.logical_key.strip()
        else:
            hub_repo = args.docker_hub_repository or ""
            if not dh_yaml.is_file():
                print(f"resolve_smoke_repository: missing {dh_yaml}", file=sys.stderr)
                return 1
            try:
                dh_images = load_test_images(dh_yaml)
            except (subprocess.CalledProcessError, ValueError, json.JSONDecodeError) as e:
                print(f"resolve_smoke_repository: {dh_yaml}: {e}", file=sys.stderr)
                return 1
            found = find_logical_key_for_hub_repo(dh_images, hub_repo)
            if found is None:
                print(
                    f"resolve_smoke_repository: no .test.images entry for Docker Hub repo "
                    f"{hub_repo!r} in {dh_yaml}",
                    file=sys.stderr,
                )
                return 1
            logical_key = found

        bundle = catalog_test_pull_bundle(k8s_root, logical_key)

        if args.json:
            d = asdict(bundle)
            d["pull_refs"] = list(bundle.pull_refs)
            print(json.dumps(d, indent=2))
        else:
            print(bundle.repository)
        return 0

    except (FileNotFoundError, ValueError, KeyError) as e:
        print(f"resolve_smoke_repository: {e}", file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as e:
        print(f"resolve_smoke_repository: yq failed: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
