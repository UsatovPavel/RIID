#!/usr/bin/env python3
"""Заменяет в YAML строки вида «image: <logical_key>» на образ из каталога (.infra.images).

Логический ключ — полное совпадение токена после «image:» со ключом в каталоге.
Строки с уже резолвнутым ref (содержат «/») не трогаются."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

IMAGE_LINE = re.compile(r"^(\s*image:\s*)([^#\n]+?)(\s*)$")
KEY_TOKEN = re.compile(r"^[a-zA-Z][a-zA-Z0-9_.]*$")


def load_catalog(path: Path) -> dict[str, str]:
    raw = subprocess.check_output(
        ["yq", "-o=json", "e", ".infra.images", str(path)],
        text=True,
    ).strip()
    data = json.loads(raw or "{}")
    out: dict[str, str] = {}
    for k, v in data.items():
        if v is None or v == "null":
            continue
        out[str(k)] = str(v)
    return out


def strip_yaml_scalar(token: str) -> str:
    t = token.strip()
    if len(t) >= 2 and ((t[0] == t[-1] == '"') or (t[0] == t[-1] == "'")):
        return t[1:-1].strip()
    return t


def resolve_file(
    path: Path,
    mapping: dict[str, str],
    *,
    write: bool,
    report_lines: list[str],
    rel_prefix: Path,
    provider: str,
) -> None:
    text = path.read_text(encoding="utf-8")
    lines_out: list[str] = []
    try:
        rel = path.relative_to(rel_prefix)
    except ValueError:
        rel = path

    changed_any = False
    for line in text.splitlines(keepends=True):
        if not line.endswith("\n"):
            line = line + "\n"
        core = line.rstrip("\n")
        m = IMAGE_LINE.match(core)
        if not m:
            lines_out.append(line)
            continue
        indent_prefix, raw_val, suffix_ws = m.groups()
        token = strip_yaml_scalar(raw_val.split("#", 1)[0])
        if "/" in token or ":" in token:
            lines_out.append(line)
            continue
        if not KEY_TOKEN.match(token):
            lines_out.append(line)
            continue
        if token not in mapping:
            raise SystemExit(
                f"{path}: unknown infra.images key in image field: {token!r}",
            )
        resolved = mapping[token]
        quoted = json.dumps(resolved)
        new_line = f"{indent_prefix}{quoted}{suffix_ws}\n"
        if new_line != line:
            changed_any = True
            report_lines.append(f"{rel}\t{token}\t{resolved}\t{provider}\n")
        lines_out.append(new_line)

    if write and changed_any:
        path.write_text("".join(lines_out), encoding="utf-8")


def iter_yaml_files(roots: list[Path]) -> list[Path]:
    found: list[Path] = []
    for root in roots:
        if not root.is_dir():
            continue
        found.extend(sorted(root.rglob("*.yaml")))
        found.extend(sorted(root.rglob("*.yml")))
    return found


def main() -> None:
    ap = argparse.ArgumentParser(description="Resolve logical image keys in YAML manifests.")
    ap.add_argument("--catalog", type=Path, required=True)
    ap.add_argument("--provider", required=True)
    ap.add_argument("--roots", type=Path, nargs="+", required=True)
    ap.add_argument("--report", type=Path, required=True)
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--rel-prefix", type=Path, required=True)
    args = ap.parse_args()

    mapping = load_catalog(args.catalog)
    report_entries: list[str] = []
    roots = [r.resolve() for r in args.roots]
    rel_prefix = args.rel_prefix.resolve()

    for ypath in iter_yaml_files(roots):
        resolve_file(
            ypath,
            mapping,
            write=args.write,
            report_lines=report_entries,
            rel_prefix=rel_prefix,
            provider=args.provider,
        )

    if report_entries:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open("a", encoding="utf-8") as fh:
            fh.writelines(report_entries)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as e:
        print(e, file=sys.stderr)
        sys.exit(1)
