#!/usr/bin/env python3
"""Handoff-бенчмарк RIID (AGENT-72 в эпике AGENT-71).

Что меряем — "handoff", передача уже скачанного образа в контейнерный рантайм:
отрезок от момента, когда Dragonfly отдал последний слой, до завершения
операции. Считается из структурных JSON-логов демона по одному trace_id
(duration_ms в логах кумулятивные от старта запроса):

    t_dl_end        = max(duration_ms) по event=source.fetch
    t_layout        = duration_ms у event=archive.build
    t_end           = duration_ms у event=engine.import

    handoff_layout  = t_layout - t_dl_end     # финализация OCI layout
    handoff_import  = t_end    - t_layout     # импорт в движок
    handoff         = t_end    - t_dl_end     # то, что оптимизируем

Сценарий (дословно из AGENT-71): 10 образов 10..50 MB → daemon → python 11-м;
меряем только python. Одна итерация = весь этот тест целиком. RIID-кэш чистится
перед каждой итерацией (рестарт демона), кэш Dragonfly не чистится никогда —
поэтому на итерациях после seed слои приходят из тёплого Dragonfly и в handoff
не попадает сетевое время.

Запуск — только через bench/Makefile.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import base64
import shutil
import signal
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from images import TARGET_IMAGE, WARMUP_IMAGES  # noqa: E402

BENCH_DIR = Path(__file__).resolve().parent
REPO_ROOT = BENCH_DIR.parent
RESULTS_DIR = BENCH_DIR / "results"
CONFIG_TEMPLATE = BENCH_DIR / "config.bench.yaml"
ENV_FILE = REPO_ROOT / "deploy" / "k8s" / "config" / ".env"
JAR = REPO_ROOT / "build" / "libs" / "riid.jar"

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


# --------------------------------------------------------------------------- #
# окружение и реестр
# --------------------------------------------------------------------------- #
def load_env_file(path: Path) -> dict[str, str]:
    """Читает KEY=VALUE из .env. Значения никуда не печатаются."""
    if not path.is_file():
        raise BenchError(f"env file not found: {path} (см. {path}.example)")
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


@dataclass(frozen=True)
class Registry:
    """Куда ходит RIID за образами и как называются в нём репозитории."""

    provider: str
    scheme: str
    host: str
    port: int
    repo_prefix: str
    strip_library: bool
    username: str
    password: str = field(repr=False)

    def map_repository(self, repository: str) -> str:
        """dockerhub-нотация → нотация текущего реестра.

        Правила повторяют deploy/k8s/providers/registry/image/mapper-common.sh
        (map_repo_by_provider / strip_library_if_needed).
        """
        mapped = repository
        if self.strip_library and mapped.startswith("library/"):
            mapped = mapped[len("library/") :]
        if self.repo_prefix:
            mapped = f"{self.repo_prefix}/{mapped}"
        return mapped


def resolve_registry(env: dict[str, str]) -> Registry:
    # RIID_BENCH_PROVIDER — аварийный обход, когда учётные данные основного
    # провайдера из .env протухли; провайдер по умолчанию берётся из .env.
    provider = (os.environ.get("RIID_BENCH_PROVIDER", "").strip().upper()
                or env.get("RIID_REGISTRY_PROVIDER", "").strip().upper()
                or "DOCKERHUB")
    if provider == "SELECTEL":
        name = env.get("REGISTRY_SELECTEL_NAME", "").strip().rstrip("/")
        if not name:
            raise BenchError("REGISTRY_SELECTEL_NAME is required in " + str(ENV_FILE))
        if "/" in name:
            host, _, prefix = name.partition("/")
        else:
            host = env.get("REGISTRY_LOGIN_HOST", "").strip() or "cr.selcloud.ru"
            prefix = name
        return Registry(
            provider=provider,
            scheme="https",
            host=host,
            port=-1,
            repo_prefix=prefix,
            strip_library=host.endswith("selcloud.ru"),
            username=env.get("RIID_SELECTEL_USER", ""),
            password=env.get("RIID_SELECTEL_TOKEN", "") or env.get("RIID_SELECTEL_PASSWORD", ""),
        )
    if provider == "DOCKERHUB":
        return Registry(
            provider=provider,
            scheme="https",
            host="registry-1.docker.io",
            port=-1,
            repo_prefix="",
            strip_library=False,
            username=env.get("RIID_DOCKERHUB_USER", ""),
            password=env.get("RIID_DOCKERHUB_TOKEN", ""),
        )
    raise BenchError(f"unsupported RIID_REGISTRY_PROVIDER={provider!r} (SELECTEL|DOCKERHUB)")


class BenchError(RuntimeError):
    pass


MANIFEST_ACCEPT = ", ".join(
    (
        "application/vnd.oci.image.index.v1+json",
        "application/vnd.oci.image.manifest.v1+json",
        "application/vnd.docker.distribution.manifest.list.v2+json",
        "application/vnd.docker.distribution.manifest.v2+json",
    )
)
WWW_AUTH_RE = re.compile(r'(\w+)="([^"]*)"')


def _basic_header(registry: Registry) -> dict[str, str]:
    if not registry.username or not registry.password:
        return {}
    token = base64.b64encode(f"{registry.username}:{registry.password}".encode()).decode()
    return {"Authorization": "Basic " + token}


def _bearer_token(registry: Registry, challenge: str) -> str | None:
    params = dict(WWW_AUTH_RE.findall(challenge))
    realm = params.get("realm")
    if not realm:
        return None
    query = []
    if params.get("service"):
        query.append(f"service={urllib.parse.quote(params['service'])}")
    if params.get("scope"):
        query.append(f"scope={urllib.parse.quote(params['scope'])}")
    url = realm + ("?" + "&".join(query) if query else "")
    # Сначала с учётными данными; если они протухли — пробуем анонимно, чтобы
    # preflight отличал "нет такого образа" от "нет доступа".
    for headers in (_basic_header(registry), {}):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
                payload = json.loads(response.read())
        except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError):
            continue
        token = payload.get("token") or payload.get("access_token")
        if token:
            return token
    return None


def manifest_status(registry: Registry, repository: str, reference: str) -> int:
    """HTTP-статус манифеста образа в реестре (200 — образ доступен)."""
    url = f"{registry.scheme}://{registry.host}/v2/{repository}/manifests/{reference}"
    headers = {"Accept": MANIFEST_ACCEPT}
    request = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status
    except urllib.error.HTTPError as first:
        if first.code != 401:
            return first.code
        token = _bearer_token(registry, first.headers.get("WWW-Authenticate", ""))
        if not token:
            return 401
        retry = urllib.request.Request(url, headers={**headers, "Authorization": "Bearer " + token}, method="GET")
        try:
            with urllib.request.urlopen(retry, timeout=30) as response:
                return response.status
        except urllib.error.HTTPError as second:
            return second.code
        except urllib.error.URLError:
            return -1
    except urllib.error.URLError:
        return -1


def preflight_images(registry: Registry) -> list[str]:
    """Проверяет, что все 11 образов реально доступны в реестре.

    Без этого ошибка вылезет через час прогона, а не через 10 секунд.
    """
    problems: list[str] = []
    for repository, tag, _ in (*WARMUP_IMAGES, TARGET_IMAGE):
        mapped = registry.map_repository(repository)
        status = manifest_status(registry, mapped, tag)
        mark = "ok " if status == 200 else "FAIL"
        print(f"  [{mark}] {mapped}:{tag} http={status}")
        if status != 200:
            problems.append(f"{mapped}:{tag} недоступен в {registry.host} (http={status})")
    return problems


# --------------------------------------------------------------------------- #
# мелкие утилиты
# --------------------------------------------------------------------------- #
def run(cmd: list[str], *, check: bool = True, timeout: int = 600, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        timeout=timeout,
        env=env,
        check=False,
    )
    if check and proc.returncode != 0:
        raise BenchError(f"command failed ({proc.returncode}): {' '.join(cmd)}\n{proc.stdout}\n{proc.stderr}")
    return proc


def tool_version(binary: str, *args: str) -> str:
    if shutil.which(binary) is None:
        return "NOT INSTALLED"
    try:
        proc = subprocess.run([binary, *(args or ("--version",))], capture_output=True, text=True, timeout=60, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return "UNKNOWN"
    out = (proc.stdout or proc.stderr).strip().splitlines()
    return out[0] if out else "UNKNOWN"


def env_report() -> dict[str, str]:
    return {
        "kernel": tool_version("uname", "-sr"),
        "java": tool_version("java", "-version"),
        "python3": tool_version("python3"),
        "podman": tool_version("podman"),
        "curl": tool_version("curl"),
        "helm": tool_version("helm", "version", "--short"),
        "kubectl": tool_version("kubectl", "version", "--client=true", "-o=yaml"),
        "minikube": tool_version("minikube", "version", "--short"),
        "yq": tool_version("yq"),
    }


def utc_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def git_commit() -> str:
    proc = subprocess.run(["git", "-C", str(REPO_ROOT), "rev-parse", "--short", "HEAD"], capture_output=True, text=True, check=False)
    return proc.stdout.strip() or "unknown"


# --------------------------------------------------------------------------- #
# проверка окружения
# --------------------------------------------------------------------------- #
def check_env(strict: bool = True) -> list[str]:
    problems: list[str] = []
    for binary in ("podman", "curl", "java"):
        if shutil.which(binary) is None:
            problems.append(f"{binary} не найден в PATH")
    if not JAR.is_file():
        problems.append(f"нет jar: {JAR} (собрать: ./gradlew shadowJar)")
    if not CONFIG_TEMPLATE.is_file():
        problems.append(f"нет шаблона конфига: {CONFIG_TEMPLATE}")
    if not ENV_FILE.is_file():
        problems.append(f"нет env-файла реестра: {ENV_FILE}")
    if not DFDAEMON_SOCKET.exists():
        problems.append(f"нет сокета dfdaemon: {DFDAEMON_SOCKET} (поднять: ./scripts/minikube-dragonfly.sh 1)")
    if not DFDAEMON_OUTPUT_DIR.is_dir():
        problems.append(f"нет каталога вывода dfdaemon: {DFDAEMON_OUTPUT_DIR}")
    elif not os.access(DFDAEMON_OUTPUT_DIR, os.W_OK):
        problems.append(f"каталог вывода dfdaemon не writable: {DFDAEMON_OUTPUT_DIR}")
    print("== check-env ==")
    for key, value in env_report().items():
        print(f"  {key:9s} {value}")

    if ENV_FILE.is_file():
        try:
            registry = resolve_registry(load_env_file(ENV_FILE))
            print(f"\n== registry: provider={registry.provider} host={registry.host} prefix={registry.repo_prefix or '-'} ==")
            if not registry.username or not registry.password:
                print(f"  (в {ENV_FILE} нет учётных данных для provider={registry.provider} — попытка анонимного доступа)")
            problems += preflight_images(registry)
        except BenchError as exc:
            problems.append(str(exc))

    if problems:
        print("\nНе готово:")
        for item in problems:
            print(f"  - {item}")
        if strict:
            raise BenchError("окружение не готово, см. список выше")
    else:
        print("\nОкружение готово.")
    return problems


# --------------------------------------------------------------------------- #
# демон
# --------------------------------------------------------------------------- #
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
        subprocess.run(["pkill", "-f", f"[r]iid.jar.*--daemon.*{self.config_path.name}"], check=False)
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


# --------------------------------------------------------------------------- #
# разбор логов
# --------------------------------------------------------------------------- #
@dataclass
class TraceTimings:
    t_dl_end_ms: int | None = None
    t_layout_ms: int | None = None
    t_end_ms: int | None = None
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


# --------------------------------------------------------------------------- #
# прогон
# --------------------------------------------------------------------------- #
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

    write_tsv(tsv_path, run_id, registry, rows)
    print_summary(tsv_path, rows)
    return tsv_path


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


def none_dash(value: int | None) -> object:
    return "-" if value is None else value


def write_tsv(path: Path, run_id: str, registry: Registry, rows: list[dict[str, object]]) -> None:
    lines = [
        f"# run_id: {run_id}",
        f"# commit: {git_commit()}",
        f"# registry: provider={registry.provider} host={registry.host} prefix={registry.repo_prefix}",
        f"# dfdaemon: {DFDAEMON_ADDR}",
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


# --------------------------------------------------------------------------- #
# record: 3 строки для Optimization.md
# --------------------------------------------------------------------------- #
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


def latest_tsv() -> Path:
    candidates = sorted(RESULTS_DIR.glob("*.tsv"))
    if not candidates:
        raise BenchError(f"нет результатов в {RESULTS_DIR}; сначала: make -C bench bench")
    return candidates[-1]


def do_record(path: Path | None) -> None:
    tsv_path = path or latest_tsv()
    meta, raw_rows = read_tsv(tsv_path)
    rows = [row for row in raw_rows if row["phase"] == "measure" and row["dirty"] == "0" and row["handoff_ms"] != "-"]
    if not rows:
        raise BenchError(f"в {tsv_path} нет чистых измеряемых итераций")

    def median_of(column: str) -> float:
        return statistics.median(int(row[column]) for row in rows)

    target = rows[0]
    size_mib = int(target["size_bytes"]) / (1024 * 1024)
    stand = f"{meta.get('env.kernel', '?')} / podman {meta.get('env.podman', '?')} / java {meta.get('env.java', '?')}"
    print(f"- MR #NN {meta.get('commit', '?')} — <что изменено> | стенд: {stand}")
    print(
        f"  {target['repository']}:{target['tag']} {size_mib:.1f}MiB N={len(rows)} dragonfly-warm "
        f"sources={target['sources']}: handoff med {median_of('handoff_ms') / 1000:.1f}s "
        f"(layout {median_of('handoff_layout_ms') / 1000:.1f} + import {median_of('handoff_import_ms') / 1000:.1f}), "
        f"wall med {median_of('wall_ms') / 1000:.1f}s, dl_end {median_of('t_dl_end_ms') / 1000:.1f}s"
    )
    print("  verdict: <baseline | −X% к предыдущей строке> ; next: <гипотеза>")


# --------------------------------------------------------------------------- #
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    run_parser = sub.add_parser("run", help="прогнать бенчмарк")
    run_parser.add_argument("--iterations", type=int, default=5, help="измеряемых итераций (плюс seed), по умолчанию 5")

    sub.add_parser("check-env", help="проверить пререквизиты")
    sub.add_parser("env-report", help="версии установленных утилит")

    record_parser = sub.add_parser("record", help="3 строки для Optimization.md")
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
