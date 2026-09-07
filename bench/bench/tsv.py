"""Разбор JSON-лога демона по trace_id + формирование TSV с результатами (AGENT-72)."""

from __future__ import annotations

import json
import statistics
from dataclasses import dataclass, field
from pathlib import Path

from checks import BenchError, Registry, env_report, git_commit
from config.settings import PAYLOAD_RE, SOURCE_RE, TSV_COLUMNS


@dataclass
class TraceTimings:
    t_dl_end_ms: int | None = None
    t_layout_ms: int | None = None
    t_end_ms: int | None = None
    t_import_ms: int | None = None
    payload_bytes: int | None = None
    sources: dict[str, int] = field(default_factory=dict)
    errors: int = 0

    @property
    def handoff_layout_ms(self) -> int | None:
        if self.t_layout_ms is None or self.t_dl_end_ms is None:
            return None
        return self.t_layout_ms - self.t_dl_end_ms

    @property
    def handoff_import_ms(self) -> int | None:
        # engine.import is the handover itself now (issue #75), so it is read
        # rather than derived. The subtraction is kept only as a fallback for
        # logs produced before that change, and it is wrong on the prefix path:
        # there the import runs inside archive.build, so t_end - t_layout ~ 0.
        if self.t_import_ms is not None:
            return self.t_import_ms
        if self.t_end_ms is None or self.t_layout_ms is None:
            return None
        return self.t_end_ms - self.t_layout_ms

    @property
    def handoff_ms(self) -> int | None:
        if self.t_end_ms is None or self.t_dl_end_ms is None:
            return None
        return self.t_end_ms - self.t_dl_end_ms


def parse_trace(log_path: Path, trace_id: str) -> TraceTimings:
    timings = TraceTimings()
    if not log_path.is_file():
        return timings
    with log_path.open("r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if not line.startswith("{") or trace_id not in line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            if record.get("trace_id") != trace_id:
                continue
            event = record.get("event")
            duration = record.get("duration_ms")
            message = record.get("message", "") or ""
            if record.get("result") == "error":
                timings.errors += 1
            if event == "source.fetch":
                if isinstance(duration, int):
                    timings.t_dl_end_ms = duration if timings.t_dl_end_ms is None else max(timings.t_dl_end_ms, duration)
                match = SOURCE_RE.search(message)
                if match:
                    source = match.group(1)
                    timings.sources[source] = timings.sources.get(source, 0) + 1
            elif event == "archive.build" and isinstance(duration, int):
                timings.t_layout_ms = duration
            elif event == "engine.import" and isinstance(duration, int):
                timings.t_import_ms = duration
                match = PAYLOAD_RE.search(message)
                if match:
                    timings.payload_bytes = int(match.group(1))
            elif event == "load.total" and isinstance(duration, int):
                timings.t_end_ms = duration
                match = PAYLOAD_RE.search(message)
                if match:
                    timings.payload_bytes = int(match.group(1))
    return timings


def sources_str(sources: dict[str, int]) -> str:
    if not sources:
        return "-"
    return ",".join(f"{name}:{count}" for name, count in sorted(sources.items()))


def is_dirty(timings: TraceTimings, exit_code: int, measured: bool) -> str:
    """measured=True — итерация идёт в агрегат только если всё чисто.

    Требование эпика: "Cache clear policy: need to clear RIID cache. Dragonfly
    not clear. Why? We bench RIID pass of downloaded from Dragonfly" — значит у
    измеряемого образа все слои обязаны прийти из p2p. Любой cache/registry
    означает, что мы меряем не тот путь.
    """
    reasons: list[str] = []
    if exit_code != 0:
        reasons.append("pull_failed")
    if timings.errors:
        reasons.append("log_errors")
    if timings.handoff_ms is None:
        reasons.append("no_timings")
    if measured:
        foreign = {name for name in timings.sources if name != "p2p"}
        if foreign:
            reasons.append("non_p2p:" + ",".join(sorted(foreign)))
        if not timings.sources:
            reasons.append("no_sources")
    return "|".join(reasons) if reasons else "0"


def none_dash(value: int | None) -> object:
    return "-" if value is None else value


def make_row(
    run_id: str,
    iteration: int,
    phase: str,
    repository: str,
    tag: str,
    size_bytes: int,
    wall_ms: int,
    exit_code: int,
    timings: TraceTimings,
    *,
    measured: bool,
) -> dict[str, object]:
    return {
        "run_id": run_id,
        "iteration": iteration,
        "phase": phase,
        "repository": repository,
        "tag": tag,
        "size_bytes": size_bytes,
        "wall_ms": wall_ms,
        "t_dl_end_ms": none_dash(timings.t_dl_end_ms),
        "handoff_layout_ms": none_dash(timings.handoff_layout_ms),
        "handoff_import_ms": none_dash(timings.handoff_import_ms),
        "handoff_ms": none_dash(timings.handoff_ms),
        "payload_bytes": none_dash(timings.payload_bytes),
        "sources": sources_str(timings.sources),
        "dirty": is_dirty(timings, exit_code, measured),
        "exit_code": exit_code,
    }


def write_tsv(path: Path, run_id: str, registry: Registry, dfdaemon_addr: str, rows: list[dict[str, object]]) -> None:
    lines = [
        f"# run_id: {run_id}",
        f"# commit: {git_commit()}",
        f"# registry: provider={registry.provider} host={registry.host} prefix={registry.repo_prefix}",
        f"# dfdaemon: {dfdaemon_addr}",
    ]
    lines += [f"# env.{key}: {value}" for key, value in env_report().items()]
    lines.append("\t".join(TSV_COLUMNS))
    for row in rows:
        lines.append("\t".join(str(row[column]) for column in TSV_COLUMNS))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def measured_rows(rows: list[dict[str, object]]) -> list[dict[str, object]]:
    return [row for row in rows if row["phase"] == "measure" and row["dirty"] == "0" and row["handoff_ms"] != "-"]


def print_summary(tsv_path: Path, rows: list[dict[str, object]]) -> None:
    clean = measured_rows(rows)
    print("\n== summary ==")
    print(f"tsv: {tsv_path}")
    if not clean:
        dirty = [row for row in rows if row["phase"] in {"measure", "seed"}]
        print("нет чистых измеряемых итераций; причины:")
        for row in dirty:
            print(f"  iter={row['iteration']} phase={row['phase']} dirty={row['dirty']} sources={row['sources']}")
        return
    handoff = [int(row["handoff_ms"]) for row in clean]
    layout = [int(row["handoff_layout_ms"]) for row in clean]
    imported = [int(row["handoff_import_ms"]) for row in clean]
    wall = [int(row["wall_ms"]) for row in clean]
    dl_end = [int(row["t_dl_end_ms"]) for row in clean]
    print(f"N={len(clean)}  handoff_ms={handoff}")
    print(f"median handoff  = {statistics.median(handoff):.0f} ms")
    print(f"       layout   = {statistics.median(layout):.0f} ms")
    print(f"       import   = {statistics.median(imported):.0f} ms")
    print(f"       wall     = {statistics.median(wall):.0f} ms")
    print(f"       dl_end   = {statistics.median(dl_end):.0f} ms")


def read_tsv(path: Path) -> tuple[dict[str, str], list[dict[str, str]]]:
    meta: dict[str, str] = {}
    rows: list[dict[str, str]] = []
    header: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#"):
            key, _, value = line[1:].strip().partition(":")
            meta[key.strip()] = value.strip()
        elif not header:
            header = line.split("\t")
        elif line.strip():
            rows.append(dict(zip(header, line.split("\t"), strict=False)))
    return meta, rows


def latest_tsv(results_dir: Path) -> Path:
    candidates = sorted(results_dir.glob("*.tsv"))
    if not candidates:
        raise BenchError(f"нет результатов в {results_dir}; сначала: make -C bench bench")
    return candidates[-1]
