"""Пути и константы handoff-бенчмарка (AGENT-72). Ничего не выполняет."""

from __future__ import annotations

import re
from pathlib import Path

BENCH_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = BENCH_DIR.parent
RESULTS_DIR = BENCH_DIR / "results"
CONFIG_TEMPLATE = BENCH_DIR / "config" / "config.bench.yaml"
ENV_FILE = REPO_ROOT / "deploy" / "k8s" / "config" / ".env"
JAR = REPO_ROOT / "build" / "libs" / "riid.jar"

LOG_DIR = BENCH_DIR / "log"
BENCH_LOG = LOG_DIR / "bench_log.md"
CHANGELOG_ENV = LOG_DIR / "changelogEnv.md"

DAEMON_SOCKET = "/tmp/riid-bench.sock"
METRICS_PORT = 9391
DFDAEMON_SOCKET = Path("/var/run/dragonfly/dfdaemon.sock")
DFDAEMON_ADDR = f"unix://{DFDAEMON_SOCKET}"
DFDAEMON_OUTPUT_DIR = DFDAEMON_SOCKET.parent / "output"

RUNTIME_ID = "podman"
PASSWORD_ENV = "RIID_BENCH_REGISTRY_PASSWORD"
DAEMON_READY_TIMEOUT_S = 90
PULL_TIMEOUT_S = 1800
CACHE_GLOB = "riid-cache-tmp-*"

TSV_COLUMNS = (
    "run_id",
    "iteration",
    "phase",
    "repository",
    "tag",
    "size_bytes",
    "wall_ms",
    "t_dl_end_ms",
    "handoff_layout_ms",
    "handoff_import_ms",
    "handoff_ms",
    "payload_bytes",
    "sources",
    "dirty",
    "exit_code",
)

PAYLOAD_RE = re.compile(r"~(\d+) B payload")
SOURCE_RE = re.compile(r"Source fetched: (\w+)")
