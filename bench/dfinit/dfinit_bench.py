#!/usr/bin/env python3
"""Standalone bench for the Dragonfly dfinit + plain `podman pull` path (AGENT-73
follow-up). Deliberately NOT part of bench/ (RIID-specific harness, parses RIID's
own JSON logs by trace_id) — this script has nothing to do with RIID: it just
times bare `podman pull` against the same image set, first (cold-ish) and
repeat (warm), so the two arms are comparable on wall-clock time only.

Usage:
    python3 dfinit_bench.py [--registry-prefix cr.selcloud.ru/riid] [--out PATH]
                             [--pause-s 3.0] [--registries-conf PATH] [--label dfinit]
                             [--skip-fresh-check]

Requires: `podman login <registry>` already done (this script does not handle
auth — bare podman needs its own credentials, separate from RIID's).

IMPORTANT — Dragonfly's own P2P cache is cluster-wide and is NEVER cleared by
`podman rmi` (that only clears local podman storage). It also isn't cleared by
this script. A "first" pull here is only genuinely cold end-to-end if this is
the *first* thing to touch these images since the Dragonfly release was
(re)installed via `bench/setup-env.sh` (seedClient uses emptyDir, so a fresh
`helm install` is the only sanctioned way to get an empty cache — never clear
storage.dir by hand). Concretely: bench.py's own RIID+Dragonfly run and this
script's dfinit run CANNOT both claim a cold-Dragonfly "first" number from the
same Dragonfly install — whichever runs second will see a warm P2P cache
seeded by the first. Run `setup-env.sh` again between the two arms if both
need genuine cold numbers. This script does a best-effort preflight check
(seed-client pod age) and aborts if the cluster looks like it's been up for a
while; pass --skip-fresh-check to override.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

# Same 10 warmup images + python target as bench/config/images.py (AGENT-72),
# duplicated here on purpose — this script has no dependency on bench/'s code.
IMAGES: tuple[tuple[str, str], ...] = (
    ("curlimages/curl", "latest"),
    ("cfcommunity/slack-notification-resource", "latest"),
    ("prom/node-exporter", "latest"),
    ("stakater/reloader", "merge-1138-ubi"),
    ("registry", "latest"),  # library/registry -> riid/registry (library/ dropped)
    ("istio/operator", "1.23.6-distroless"),
    ("bitnami/sealed-secrets-controller", "latest"),
    ("ubuntu", "latest"),  # library/ubuntu -> riid/ubuntu
    ("google/cadvisor", "latest"),
    ("presearch/node", "latest"),
    ("python", "latest"),  # library/python -> riid/python, TARGET image
)


def run(cmd: list[str], env: dict | None = None) -> tuple[int, float]:
    start = time.monotonic()
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False, env=env)
    elapsed_ms = (time.monotonic() - start) * 1000
    if proc.returncode != 0:
        print(f"    !! exit={proc.returncode} stderr={proc.stderr.strip()[:300]}", file=sys.stderr)
    return proc.returncode, elapsed_ms


def timed_pull(ref: str, env: dict | None = None) -> tuple[int, float]:
    return run(["podman", "pull", "-q", ref], env=env)


def rmi(ref: str, env: dict | None = None) -> None:
    subprocess.run(["podman", "rmi", "-f", ref], capture_output=True, text=True, check=False, env=env)


def seed_client_age_s() -> float | None:
    """Best-effort: seconds since the seed-client pod started, via kubectl. None
    if it can't be determined (no cluster, no permissions, unexpected output) --
    callers should treat that as "can't verify" rather than "definitely fresh"."""
    proc = subprocess.run(
        ["kubectl", "get", "pods", "-n", "dragonfly-system",
         "-l", "component=seed-client",
         "-o", "jsonpath={.items[0].status.startTime}"],
        capture_output=True, text=True, check=False,
    )
    if proc.returncode != 0 or not proc.stdout.strip():
        return None
    try:
        started = datetime.strptime(proc.stdout.strip(), "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        return None
    return (datetime.now(timezone.utc) - started).total_seconds()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry-prefix", default="cr.selcloud.ru/riid",
                         help="registry host + org prefix (default: cr.selcloud.ru/riid, matches bench/'s Selectel mapping)")
    parser.add_argument("--out", default=None, help="TSV output path (default: results/<run_id>_dfinit.tsv next to this script)")
    parser.add_argument("--pause-s", type=float, default=3.0,
                         help="pause (seconds) between rmi/pull steps -- a first run without this showed "
                              "'repeat' pulls growing steadily slower than 'first' (up to 41s on the target "
                              "image), suspected dfdaemon-side contention from back-to-back rmi+pull; this "
                              "pause tests that hypothesis (default: 3.0)")
    parser.add_argument("--registries-conf", default=None,
                         help="path for CONTAINERS_REGISTRIES_CONF env override -- use this to run a "
                              "no-mirror control (point at a registries.conf without [[registry.mirror]]) "
                              "without touching the root-owned /etc/containers/registries.conf")
    parser.add_argument("--label", default="dfinit",
                         help="label included in the output filename, e.g. 'no-mirror-control' (default: dfinit)")
    parser.add_argument("--skip-fresh-check", action="store_true",
                         help="skip the seed-client pod-age preflight check (e.g. for a deliberate "
                              "warm-cache 'repeat-only' run, or the no-mirror control which never "
                              "touches Dragonfly anyway)")
    parser.add_argument("--max-pod-age-s", type=float, default=120.0,
                         help="abort if the seed-client pod is older than this many seconds -- a proxy "
                              "for 'Dragonfly's P2P cache might already be warm from other pulls' "
                              "(default: 120)")
    args = parser.parse_args()

    if not args.skip_fresh_check:
        age_s = seed_client_age_s()
        if age_s is None:
            print("[dfinit-bench] WARNING: could not determine seed-client pod age (no cluster access?) "
                  "-- proceeding, but 'first' numbers may not be cold. Use --skip-fresh-check to silence.",
                  file=sys.stderr)
        elif age_s > args.max_pod_age_s:
            print(f"[dfinit-bench] ABORT: seed-client pod is {age_s:.0f}s old (> {args.max_pod_age_s:.0f}s) "
                  "-- Dragonfly's P2P cache is very likely already warm from earlier pulls, so 'first' "
                  "numbers from this run would not be a genuine cold measurement. Reinstall Dragonfly "
                  "fresh via bench/setup-env.sh first, or pass --skip-fresh-check to run anyway.",
                  file=sys.stderr)
            return 1

    env = dict(os.environ)
    if args.registries_conf:
        env["CONTAINERS_REGISTRIES_CONF"] = args.registries_conf

    run_id = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    out_path = Path(args.out) if args.out else Path(__file__).parent / "results" / f"{run_id}_{args.label}.tsv"
    out_path.parent.mkdir(parents=True, exist_ok=True)

    rows: list[str] = ["run_id\tphase\trepository\ttag\twall_ms\texit_code"]

    print(f"[dfinit-bench] run_id={run_id} registry_prefix={args.registry_prefix}")
    print(f"[dfinit-bench] results: {out_path}")

    for repo, tag in IMAGES:
        ref = f"{args.registry_prefix}/{repo}:{tag}"

        # first (cold-ish w.r.t. local podman storage; Dragonfly's own P2P cache
        # is NOT cleared by this script -- see caveat in ResearchDragonflyPodmanMirror.md)
        rmi(ref, env=env)
        time.sleep(args.pause_s)
        code, first_ms = timed_pull(ref, env=env)
        print(f"[dfinit-bench] first  {repo}:{tag} wall={first_ms:.0f}ms exit={code}")
        rows.append(f"{run_id}\tfirst\t{repo}\t{tag}\t{first_ms:.0f}\t{code}")

        # repeat (warm local podman storage was just populated by 'first' -- rmi
        # again to force a genuine re-pull, not a no-op "already have it")
        rmi(ref, env=env)
        time.sleep(args.pause_s)
        code, repeat_ms = timed_pull(ref, env=env)
        print(f"[dfinit-bench] repeat {repo}:{tag} wall={repeat_ms:.0f}ms exit={code}")
        rows.append(f"{run_id}\trepeat\t{repo}\t{tag}\t{repeat_ms:.0f}\t{code}")

    out_path.write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"[dfinit-bench] done, wrote {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
