#!/usr/bin/env python3
"""Handoff-бенчмарк RIID (AGENT-72 в эпике AGENT-71).

Что меряем — "handoff", передача уже скачанного образа в контейнерный рантайм:
отрезок от момента, когда Dragonfly отдал последний слой, до завершения
операции. Считается из структурных JSON-логов демона по одному trace_id.
duration_ms у каждого события — его собственный интервал, но события вложены
(load.total ⊃ archive.build ⊃ source.fetch), поэтому выглядят кумулятивными:

    t_dl_end        = max(duration_ms) по event=source.fetch
    t_layout        = duration_ms у event=archive.build
    t_import        = duration_ms у event=engine.import   # сама передача в движок
    t_end           = duration_ms у event=load.total      # манифест → образ в движке

    handoff_layout  = t_layout - t_dl_end     # финализация OCI layout
    handoff_import  = t_import                # импорт в движок, берётся напрямую
    handoff         = t_end    - t_dl_end     # то, что оптимизируем

Сценарий (дословно из AGENT-71): 10 образов 10..50 MB → daemon → python 11-м;
меряем только python. Одна итерация = весь этот тест целиком.

Итерации: `--iterations N` даёт N+1 прогонов. Прогон 0 — "seed": перед НИМ, как
и перед любой другой итерацией, RIID-кэш и podman чистятся (см. do_run) — но
Dragonfly в самом начале ещё мог быть холодным, поэтому его собственные числа
не входят в агрегат, он существует только чтобы прогреть Dragonfly (кэш
Dragonfly не чистится никогда — его слои остаются доступны и после итерации).
Прогоны 1..N — "measure": те же чистки RIID-кэша/podman перед стартом, но
теперь Dragonfly уже тёплый, сетевое время не попадает в measure-числа, и
по ним считается медиана. Разница seed/measure — только в порядке (seed идёт
первой) и в том, что seed не входит в агрегат.

Запуск — только через bench/Makefile.
"""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from checks import BenchError, Registry, check_env, env_report, load_env_file, resolve_registry, run, utc_stamp  # noqa: E402
from config.images import TARGET_IMAGE, WARMUP_IMAGES  # noqa: E402
from config.settings import (  # noqa: E402
    CACHE_GLOB,
    CONFIG_TEMPLATE,
    DAEMON_READY_TIMEOUT_S,
    DAEMON_SOCKET,
    DFDAEMON_ADDR,
    ENV_FILE,
    JAR,
    METRICS_PORT,
    PASSWORD_ENV,
    PULL_TIMEOUT_S,
    REPO_ROOT,
    RESULTS_DIR,
    RUNTIME_ID,
)
from record import do_record  # noqa: E402
from tsv import make_row, parse_trace, print_summary, sources_str, write_tsv  # noqa: E402

BENCH_LOCK = Path("/tmp/riid-bench.lock")


class Daemon:
    """Жизненный цикл RIID-демона: рестарт = чистка RIID-кэша (кэш живёт в

    PathSupport.tempDirPath("riid-cache-tmp-"), т.е. создаётся на инстанс
    демона — см. src/main/java/riid/cache/oci/TempFileCacheAdapter.java).
    """

    def __init__(self, config_path: Path, log_path: Path, registry: Registry) -> None:
        self.config_path = config_path
        self.log_path = log_path
        self.registry = registry
        self.process: subprocess.Popen[bytes] | None = None

    def restart(self) -> None:
        self.stop()
        self._clear_riid_cache()
        cmd = [
            "java",
            "-jar",
            str(JAR),
            "--daemon",
            "--config",
            str(self.config_path),
        ]
        if self.registry.username:
            cmd += ["--username", self.registry.username, "--password-env", PASSWORD_ENV]
        child_env = dict(os.environ)
        child_env[PASSWORD_ENV] = self.registry.password
        log_handle = self.log_path.open("ab")
        self.process = subprocess.Popen(cmd, stdout=log_handle, stderr=subprocess.STDOUT, env=child_env, cwd=str(REPO_ROOT))
        self._wait_ready()

    def stop(self) -> None:
        if self.process is not None and self.process.poll() is None:
            self.process.send_signal(signal.SIGTERM)
            try:
                self.process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=30)
        self.process = None
        config_pattern = re.escape(str(self.config_path))
        subprocess.run(["pkill", "-f", f"[r]iid.jar.*--daemon.*{config_pattern}"], check=False)
        Path(DAEMON_SOCKET).unlink(missing_ok=True)

    @staticmethod
    def _clear_riid_cache() -> None:
        for path in Path("/tmp").glob(CACHE_GLOB):
            shutil.rmtree(path, ignore_errors=True)

    def _wait_ready(self) -> None:
        deadline = time.monotonic() + DAEMON_READY_TIMEOUT_S
        while time.monotonic() < deadline:
            if self.process is not None and self.process.poll() is not None:
                raise BenchError(f"демон завершился на старте, см. {self.log_path}")
            if Path(DAEMON_SOCKET).exists():
                proc = subprocess.run(
                    [
                        "curl", "-sS", "--max-time", "2", "--unix-socket", DAEMON_SOCKET,
                        "-o", "/dev/null", "-w", "%{http_code}", "-X", "GET", "http://localhost/pull",
                    ],
                    capture_output=True,
                    text=True,
                    check=False,
                )
                if proc.returncode == 0 and proc.stdout.strip() in {"400", "404", "405"}:
                    return
            time.sleep(0.5)
        raise BenchError(f"демон не поднялся за {DAEMON_READY_TIMEOUT_S}s на сокете {DAEMON_SOCKET}")


def post_pull(repository: str, reference: str, trace_id: str) -> tuple[int, int, str]:
    """POST /pull через UDS. Возвращает (wall_ms, exit_code, http_status)."""
    body = json.dumps({"repository": repository, "reference": reference, "runtimeId": RUNTIME_ID})
    cmd = [
        "curl", "-sS", "--max-time", str(PULL_TIMEOUT_S), "--unix-socket", DAEMON_SOCKET,
        "-X", "POST", "http://localhost/pull",
        "-H", "Content-Type: application/json",
        "-H", f"X-Trace-Id: {trace_id}",
        "-w", "\n%{http_code}",
        "-d", body,
    ]
    started = time.monotonic()
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=PULL_TIMEOUT_S + 60, check=False)
    wall_ms = int((time.monotonic() - started) * 1000)
    status = proc.stdout.strip().splitlines()[-1] if proc.stdout.strip() else ""
    exit_code = proc.returncode if proc.returncode != 0 else (0 if status == "200" else 1)
    if exit_code != 0:
        sys.stderr.write(f"[bench] pull failed repo={repository}:{reference} http={status} curl={proc.returncode}\n")
        sys.stderr.write(f"[bench] response: {proc.stdout.strip()[:500]}\n")
    return wall_ms, exit_code, status


def podman_reset() -> None:
    """Идентичное состояние podman перед каждой итерацией.

    Выборочного rmi недостаточно: containers-storage дедуплицирует слои по
    digest, и уже присутствующие слои не распаковываются заново — тогда
    handoff_import (76% времени в кластере) не воспроизводится.
    """
    run(["podman", "system", "prune", "-af"], check=False, timeout=900)


def render_config(run_dir: Path, registry: Registry) -> Path:
    template = CONFIG_TEMPLATE.read_text(encoding="utf-8")
    rendered = (
        template.replace("__REGISTRY_SCHEME__", registry.scheme)
        .replace("__REGISTRY_HOST__", registry.host)
        .replace("__REGISTRY_PORT__", str(registry.port))
        .replace("__DAEMON_SOCKET__", DAEMON_SOCKET)
        .replace("__DFDAEMON_ADDR__", DFDAEMON_ADDR)
        .replace("__METRICS_PORT__", str(METRICS_PORT))
    )
    path = run_dir / "config.yaml"
    path.write_text(rendered, encoding="utf-8")
    return path


def do_run(iterations: int) -> Path:
    with BENCH_LOCK.open("a+", encoding="utf-8") as lock_handle:
        try:
            fcntl.flock(lock_handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            lock_handle.seek(0)
            owner = lock_handle.read().strip() or "unknown"
            raise BenchError(f"другой benchmark уже запущен (pid={owner})") from exc
        lock_handle.seek(0)
        lock_handle.truncate()
        lock_handle.write(str(os.getpid()))
        lock_handle.flush()
        return _do_run(iterations)


def _do_run(iterations: int) -> Path:
    check_env(strict=True)
    env = load_env_file(ENV_FILE)
    registry = resolve_registry(env)

    run_id = utc_stamp()
    run_dir = RESULTS_DIR / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    log_path = run_dir / "daemon.log"
    tsv_path = RESULTS_DIR / f"{run_id}.tsv"
    config_path = render_config(run_dir, registry)

    rows: list[dict[str, object]] = []
    daemon = Daemon(config_path, log_path, registry)

    print(f"[bench] run_id={run_id} provider={registry.provider} host={registry.host} iterations={iterations}")
    print(f"[bench] результаты: {tsv_path}")

    try:
        for iteration in range(iterations + 1):
            phase = "seed" if iteration == 0 else "measure"
            print(f"[bench] --- iteration {iteration}/{iterations} ({phase}) ---")
            podman_reset()
            daemon.restart()

            for index, (repository, tag, size_bytes) in enumerate(WARMUP_IMAGES):
                trace_id = f"bench-{run_id}-{iteration}-warm-{index}"
                mapped = registry.map_repository(repository)
                wall_ms, exit_code, _ = post_pull(mapped, tag, trace_id)
                timings = parse_trace(log_path, trace_id)
                rows.append(make_row(run_id, iteration, "warm", repository, tag, size_bytes, wall_ms, exit_code, timings, measured=False))
                print(f"[bench]   warm {index + 1:2d}/10 {repository}:{tag} wall={wall_ms}ms handoff={timings.handoff_ms}ms exit={exit_code}")

            repository, tag, size_bytes = TARGET_IMAGE
            trace_id = f"bench-{run_id}-{iteration}-target"
            mapped = registry.map_repository(repository)
            wall_ms, exit_code, _ = post_pull(mapped, tag, trace_id)
            timings = parse_trace(log_path, trace_id)
            row = make_row(run_id, iteration, phase, repository, tag, size_bytes, wall_ms, exit_code, timings, measured=(phase == "measure"))
            rows.append(row)
            print(
                f"[bench]   TARGET {repository}:{tag} wall={wall_ms}ms dl_end={timings.t_dl_end_ms}ms "
                f"handoff={timings.handoff_ms}ms (layout={timings.handoff_layout_ms} import={timings.handoff_import_ms}) "
                f"sources={sources_str(timings.sources)} dirty={row['dirty']}"
            )
    finally:
        daemon.stop()

    write_tsv(tsv_path, run_id, registry, DFDAEMON_ADDR, rows)
    print_summary(tsv_path, rows)
    return tsv_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    run_parser = sub.add_parser("run", help="прогнать бенчмарк")
    run_parser.add_argument("--iterations", type=int, required=True, help="измеряемых итераций (плюс seed); обязателен, дефолта нет намеренно")

    sub.add_parser("check-env", help="проверить пререквизиты")
    sub.add_parser("env-report", help="версии установленных утилит")

    record_parser = sub.add_parser("record", help="запись для bench/log/bench_log.md")
    record_parser.add_argument("--results", type=Path, default=None)

    args = parser.parse_args()
    try:
        if args.command == "run":
            do_run(args.iterations)
        elif args.command == "check-env":
            check_env(strict=True)
        elif args.command == "env-report":
            for key, value in env_report().items():
                print(f"{key:9s} {value}")
        elif args.command == "record":
            do_record(args.results)
    except BenchError as exc:
        sys.stderr.write(f"bench: {exc}\n")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
