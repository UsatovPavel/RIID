"""Мелкие утилиты запуска команд + проверка окружения и реестра (AGENT-72).

Всё, что нужно до запуска самого бенча: разбор `.env`, резолв реестра/учётных
данных, preflight-проверка доступности всех 11 образов, `check-env`.
"""

from __future__ import annotations

import base64
import json
import os
import re
import shutil
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from config.images import TARGET_IMAGE, WARMUP_IMAGES
from config.settings import CONFIG_TEMPLATE, DFDAEMON_OUTPUT_DIR, DFDAEMON_SOCKET, ENV_FILE, JAR, REPO_ROOT


class BenchError(RuntimeError):
    pass


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
    # провайдера из .env протухли. Дефолта на DOCKERHUB нет намеренно: если
    # провайдер не задан явно ни там, ни там — это ошибка конфигурации, а не
    # повод тихо скачивать не из того реестра, что заявлен в замере.
    provider = (
        os.environ.get("RIID_BENCH_PROVIDER", "").strip().upper()
        or env.get("RIID_REGISTRY_PROVIDER", "").strip().upper()
    )
    if not provider:
        raise BenchError("RIID_REGISTRY_PROVIDER is required in " + str(ENV_FILE))
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
