"""3-строчная запись для bench_log.md из последнего чистого TSV (AGENT-72).

Строку 3 (verdict/next) дописывает человек — см. bench/log/bench_log.md §5.
"""

from __future__ import annotations

import statistics
from pathlib import Path

from checks import BenchError
from config.settings import RESULTS_DIR
from tsv import latest_tsv, read_tsv


def do_record(path: Path | None) -> None:
    tsv_path = path or latest_tsv(RESULTS_DIR)
    meta, raw_rows = read_tsv(tsv_path)
    rows = [row for row in raw_rows if row["phase"] == "measure" and row["dirty"] == "0" and row["handoff_ms"] != "-"]
    if not rows:
        raise BenchError(f"в {tsv_path} нет чистых измеряемых итераций")

    def median_of(column: str) -> float:
        return statistics.median(int(row[column]) for row in rows)

    target = rows[0]
    size_mib = int(target["size_bytes"]) / (1024 * 1024)
    stand = f"{meta.get('env.kernel', '?')} / podman {meta.get('env.podman', '?')} / java {meta.get('env.java', '?')}"
    handoff_s = median_of("handoff_ms") / 1000

    # Результат — одно число, крупно, первым; дальше — разложение и контекст.
    print(f"### Handoff: {handoff_s:.1f}s")
    print()
    print(f"- MR #NN {meta.get('commit', '?')} — <что изменено> | стенд: {stand}")
    print(
        f"  {target['repository']}:{target['tag']} {size_mib:.1f}MiB N={len(rows)} dragonfly-warm "
        f"sources={target['sources']}: handoff med {handoff_s:.1f}s "
        f"(layout {median_of('handoff_layout_ms') / 1000:.1f} + import {median_of('handoff_import_ms') / 1000:.1f}), "
        f"wall med {median_of('wall_ms') / 1000:.1f}s, dl_end {median_of('t_dl_end_ms') / 1000:.1f}s"
    )
    print("  verdict: <baseline | −X% к предыдущей строке> ; next: <гипотеза>")
