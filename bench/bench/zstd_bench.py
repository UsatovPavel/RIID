#!/usr/bin/env python3
"""AGENT-97: plain OCI zstd versus gzip through Podman and real RIID -> Podman."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import shlex
import shutil
import signal
import socket
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

from checks import BenchError, git_commit, run, tool_version  # noqa: E402
from tsv import parse_trace, sources_str  # noqa: E402

BENCH_DIR = Path(__file__).resolve().parent
REPO_ROOT = BENCH_DIR.parent
RESULTS_DIR = BENCH_DIR / "results"
CONFIG_TEMPLATE = BENCH_DIR / "config" / "config.zstd-bench.yaml"
JAR = REPO_ROOT / "build" / "libs" / "riid.jar"
DEFAULT_ARTIFACTS = RESULTS_DIR / "zstd-artifacts.json"
MANIFEST_ACCEPT = ", ".join(
    (
        "application/vnd.oci.image.manifest.v1+json",
        "application/vnd.docker.distribution.manifest.v2+json",
    )
)
OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json"
OCI_CONFIG = "application/vnd.oci.image.config.v1+json"
OCI_GZIP = "application/vnd.oci.image.layer.v1.tar+gzip"
OCI_ZSTD = "application/vnd.oci.image.layer.v1.tar+zstd"
RESULT_COLUMNS = (
    "run_id",
    "iteration",
    "phase",
    "arm",
    "codec",
    "path",
    "artifact_digest",
    "layer_bytes",
    "wall_ms",
    "t_dl_end_ms",
    "handoff_layout_ms",
    "handoff_import_ms",
    "handoff_ms",
    "sources",
    "smoke",
    "exit_code",
    "error",
    "daemon_log",
)


@dataclass(frozen=True)
class Registry:
    scheme: str
    host: str
    port: int

    @property
    def authority(self) -> str:
        default = (self.scheme == "http" and self.port == 80) or (self.scheme == "https" and self.port == 443)
        return self.host if self.port < 0 or default else f"{self.host}:{self.port}"

    @property
    def tls_verify(self) -> bool:
        return self.scheme == "https"

    def api_url(self, path: str) -> str:
        return f"{self.scheme}://{self.authority}{path}"


@dataclass(frozen=True)
class Arm:
    name: str
    codec: str
    path: str


ARMS = (
    Arm("podman-gzip", "gzip", "podman"),
    Arm("podman-zstd", "zstd", "podman"),
    Arm("riid-podman-gzip", "gzip", "riid-podman"),
    Arm("riid-podman-zstd", "zstd", "riid-podman"),
)


def utc_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_registry(raw: str) -> Registry:
    parsed = urllib.parse.urlparse(raw)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise BenchError(f"registry must be an http(s) URL, got {raw!r}")
    if parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
        raise BenchError("registry URL must contain only scheme, host and optional port")
    port = parsed.port if parsed.port is not None else (443 if parsed.scheme == "https" else 80)
    return Registry(parsed.scheme, parsed.hostname, port)


def registry_json(registry: Registry, repository: str, kind: str, reference: str) -> tuple[dict[str, Any], str]:
    quoted_repository = "/".join(urllib.parse.quote(part, safe="") for part in repository.split("/"))
    quoted_reference = urllib.parse.quote(reference, safe=":")
    request = urllib.request.Request(
        registry.api_url(f"/v2/{quoted_repository}/{kind}/{quoted_reference}"),
        headers={"Accept": MANIFEST_ACCEPT},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = json.loads(response.read())
            digest = response.headers.get("Docker-Content-Digest", "")
            return payload, digest
    except (OSError, urllib.error.URLError, urllib.error.HTTPError, json.JSONDecodeError) as exc:
        raise BenchError(f"registry request failed for {repository}/{kind}/{reference}: {exc}") from exc


def push_variant(
    source: str,
    registry: Registry,
    repository: str,
    tag: str,
    codec: str,
    level: int,
) -> tuple[str, int, list[str]]:
    with tempfile.NamedTemporaryFile(prefix=f"riid-{codec}-digest-", delete=False) as handle:
        digest_path = Path(handle.name)
    destination = f"docker://{registry.authority}/{repository}:{tag}"
    command = [
        "podman",
        "push",
        "--format",
        "oci",
        "--compression-format",
        codec,
        "--compression-level",
        str(level),
        "--force-compression",
        f"--tls-verify={'true' if registry.tls_verify else 'false'}",
        "--digestfile",
        str(digest_path),
        source,
        destination,
    ]
    started = time.monotonic()
    try:
        run(command, timeout=3600)
        digest = digest_path.read_text(encoding="utf-8").strip()
    finally:
        digest_path.unlink(missing_ok=True)
    if not digest.startswith("sha256:"):
        raise BenchError(f"podman did not report a manifest digest for {destination}")
    return digest, int((time.monotonic() - started) * 1000), command


def inspect_variant(registry: Registry, repository: str, digest: str, expected_layer_type: str) -> dict[str, Any]:
    manifest, header_digest = registry_json(registry, repository, "manifests", digest)
    if header_digest and header_digest != digest:
        raise BenchError(f"registry returned {header_digest} for immutable reference {digest}")
    if manifest.get("mediaType") != OCI_MANIFEST:
        raise BenchError(f"{digest}: expected OCI manifest, got {manifest.get('mediaType')!r}")
    config_descriptor = manifest.get("config") or {}
    if config_descriptor.get("mediaType") != OCI_CONFIG:
        raise BenchError(f"{digest}: expected OCI config, got {config_descriptor.get('mediaType')!r}")
    layers = manifest.get("layers") or []
    if not layers:
        raise BenchError(f"{digest}: manifest contains no layers")
    wrong_types = [layer.get("mediaType") for layer in layers if layer.get("mediaType") != expected_layer_type]
    if wrong_types:
        raise BenchError(f"{digest}: expected only {expected_layer_type}, got {wrong_types}")
    config_digest = config_descriptor.get("digest", "")
    config, _ = registry_json(registry, repository, "blobs", config_digest)
    diff_ids = ((config.get("rootfs") or {}).get("diff_ids")) or []
    if len(diff_ids) != len(layers):
        raise BenchError(f"{digest}: {len(layers)} layers but {len(diff_ids)} diff_ids")
    return {
        "manifest_digest": digest,
        "manifest_media_type": manifest["mediaType"],
        "config_digest": config_digest,
        "config_media_type": config_descriptor["mediaType"],
        "diff_ids": diff_ids,
        "layer_media_types": [layer["mediaType"] for layer in layers],
        "layer_digests": [layer["digest"] for layer in layers],
        "layer_sizes": [int(layer["size"]) for layer in layers],
        "layer_bytes": sum(int(layer["size"]) for layer in layers),
    }


def prepare(args: argparse.Namespace) -> Path:
    registry = parse_registry(args.registry)
    if shutil.which("podman") is None:
        raise BenchError("podman not found")
    if not args.source.strip() or not args.repository.strip():
        raise BenchError("source and repository must not be blank")

    variants: dict[str, dict[str, Any]] = {}
    for codec, tag, level, media_type in (
        ("gzip", args.gzip_tag, args.gzip_level, OCI_GZIP),
        ("zstd", args.zstd_tag, args.zstd_level, OCI_ZSTD),
    ):
        print(f"[prepare] pushing {codec} level={level} -> {registry.authority}/{args.repository}:{tag}")
        digest, conversion_ms, command = push_variant(
            args.source, registry, args.repository, tag, codec, level
        )
        inspected = inspect_variant(registry, args.repository, digest, media_type)
        variants[codec] = {
            "tag": tag,
            "compression_level": level,
            "conversion_ms": conversion_ms,
            "command": command,
            **inspected,
        }

    gzip = variants["gzip"]
    zstd = variants["zstd"]
    if gzip["config_digest"] != zstd["config_digest"]:
        raise BenchError("artifact gate failed: gzip and zstd config digests differ")
    if gzip["diff_ids"] != zstd["diff_ids"]:
        raise BenchError("artifact gate failed: gzip and zstd rootfs.diff_ids differ")
    if len(gzip["layer_sizes"]) != len(zstd["layer_sizes"]):
        raise BenchError("artifact gate failed: gzip and zstd layer counts differ")
    if gzip["manifest_digest"] == zstd["manifest_digest"]:
        raise BenchError("artifact gate failed: gzip and zstd manifest digests are identical")

    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema_version": 1,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "source": args.source,
        "registry": {"scheme": registry.scheme, "host": registry.host, "port": registry.port},
        "repository": args.repository,
        "artifact_gate": {
            "same_config_digest": True,
            "same_ordered_diff_ids": True,
            "same_layer_count": True,
            "separate_immutable_manifest_digests": gzip["manifest_digest"] != zstd["manifest_digest"],
        },
        "variants": variants,
        "toolchain": {"podman": tool_version("podman")},
    }
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    ratio = zstd["layer_bytes"] / gzip["layer_bytes"]
    print(f"[prepare] verified pair: config={gzip['config_digest']} layers={len(gzip['diff_ids'])}")
    print(
        f"[prepare] gzip={gzip['layer_bytes']} B zstd={zstd['layer_bytes']} B "
        f"zstd/gzip={ratio:.3f}; metadata={output}"
    )
    return output


def load_artifacts(path: Path) -> tuple[dict[str, Any], Registry]:
    try:
        artifacts = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise BenchError(f"cannot read artifact metadata {path}: {exc}") from exc
    if artifacts.get("schema_version") != 1:
        raise BenchError(f"unsupported artifact metadata schema in {path}")
    raw_registry = artifacts.get("registry") or {}
    registry = Registry(raw_registry.get("scheme", ""), raw_registry.get("host", ""), raw_registry.get("port", -1))
    repository = artifacts.get("repository", "")
    for codec, media_type in (("gzip", OCI_GZIP), ("zstd", OCI_ZSTD)):
        recorded = artifacts.get("variants", {}).get(codec) or {}
        digest = recorded.get("manifest_digest", "")
        live = inspect_variant(registry, repository, digest, media_type)
        for invariant in ("config_digest", "diff_ids", "layer_media_types", "layer_sizes"):
            if live[invariant] != recorded.get(invariant):
                raise BenchError(f"{codec} artifact changed: {invariant} differs from {path}")
    gzip = artifacts["variants"]["gzip"]
    zstd = artifacts["variants"]["zstd"]
    if gzip["config_digest"] != zstd["config_digest"] or gzip["diff_ids"] != zstd["diff_ids"]:
        raise BenchError("artifact pair is not rootfs-equivalent")
    return artifacts, registry


def isolated_podman_env(root: Path) -> dict[str, str]:
    graphroot = root / "graphroot"
    runroot = Path("/tmp") / f"riid-zr-{uuid.uuid4().hex[:12]}"
    tmpdir = root / "tmp"
    wrapper_dir = root / "bin"
    for directory in (graphroot, runroot, tmpdir, wrapper_dir):
        directory.mkdir(parents=True, exist_ok=True)
    podman_binary = shutil.which("podman")
    if podman_binary is None:
        raise BenchError("podman not found")
    wrapper = wrapper_dir / "podman"
    wrapper.write_text(
        "#!/bin/sh\n"
        f"exec {shlex.quote(podman_binary)} --root {shlex.quote(str(graphroot))} "
        f"--runroot {shlex.quote(str(runroot))} --storage-driver overlay \"$@\"\n",
        encoding="utf-8",
    )
    wrapper.chmod(0o755)
    env = dict(os.environ)
    env.pop("CONTAINERS_STORAGE_CONF", None)
    env["PATH"] = str(wrapper_dir) + os.pathsep + env.get("PATH", "")
    env["TMPDIR"] = str(tmpdir)
    env["RIID_ZSTD_BENCH_RUNROOT"] = str(runroot)
    return env


def require_fresh_podman_store(env: dict[str, str], expected_graphroot: Path) -> None:
    info = subprocess.run(
        ["podman", "info", "--format", "{{.Store.GraphRoot}}"],
        capture_output=True,
        text=True,
        timeout=60,
        env=env,
        check=False,
    )
    if info.returncode != 0:
        raise BenchError(f"isolated podman info failed: {info.stderr.strip()[:300]}")
    actual_graphroot = Path(info.stdout.strip()).resolve()
    if actual_graphroot != expected_graphroot.resolve():
        raise BenchError(f"podman isolation failed: expected graphroot {expected_graphroot}, got {actual_graphroot}")
    images = subprocess.run(
        ["podman", "images", "--quiet"],
        capture_output=True,
        text=True,
        timeout=60,
        env=env,
        check=False,
    )
    if images.returncode != 0:
        raise BenchError(f"isolated podman image listing failed: {images.stderr.strip()[:300]}")
    if images.stdout.strip():
        raise BenchError("podman isolation failed: fresh graphroot already contains images")


def cleanup_isolated_podman(env: dict[str, str], sample_root: Path) -> str:
    errors: list[str] = []
    graphroot = sample_root / "graphroot"
    namespace_cleanup_error = ""
    if graphroot.exists():
        # Overlay entries can be owned by subordinate UIDs. Enter Podman's user
        # namespace and remove only this sample's explicit graphroot. `system
        # reset` is intentionally forbidden because rootless Podman also keeps
        # process-global runtime state outside --root/--runroot.
        cleanup = subprocess.run(
            [
                "podman",
                "unshare",
                "python3",
                "-c",
                "import shutil,sys; shutil.rmtree(sys.argv[1])",
                str(graphroot),
            ],
            capture_output=True,
            text=True,
            timeout=300,
            env=env,
            check=False,
        )
        if cleanup.returncode != 0:
            detail = (cleanup.stderr or cleanup.stdout).strip().replace("\n", " ")[:300]
            namespace_cleanup_error = detail
    isolated_runroot = env.get("RIID_ZSTD_BENCH_RUNROOT")
    if isolated_runroot:
        shutil.rmtree(isolated_runroot, ignore_errors=True)
        if Path(isolated_runroot).exists():
            errors.append(f"isolated runroot survived cleanup: {isolated_runroot}")
    shutil.rmtree(sample_root, ignore_errors=True)
    if sample_root.exists():
        suffix = f": {namespace_cleanup_error}" if namespace_cleanup_error else ""
        errors.append(f"isolated graphroot survived cleanup: {sample_root}{suffix}")
    return "; ".join(errors)


def render_config(path: Path, registry: Registry, socket_path: Path) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as metrics_socket:
        metrics_socket.bind(("127.0.0.1", 0))
        metrics_port = metrics_socket.getsockname()[1]
    rendered = (
        CONFIG_TEMPLATE.read_text(encoding="utf-8")
        .replace("__REGISTRY_SCHEME__", registry.scheme)
        .replace("__REGISTRY_HOST__", registry.host)
        .replace("__REGISTRY_PORT__", str(registry.port))
        .replace("__DAEMON_SOCKET__", str(socket_path))
        .replace("__METRICS_PORT__", str(metrics_port))
    )
    if "__" in rendered:
        raise BenchError("unresolved placeholder in zstd benchmark config")
    path.write_text(rendered, encoding="utf-8")


class RiidDaemon:
    def __init__(self, config_path: Path, socket_path: Path, log_path: Path, env: dict[str, str], java_tmp: Path) -> None:
        self.config_path = config_path
        self.socket_path = socket_path
        self.log_path = log_path
        self.env = env
        self.java_tmp = java_tmp
        self.process: subprocess.Popen[bytes] | None = None
        self.log_handle: Any = None

    def start(self) -> None:
        self.java_tmp.mkdir(parents=True, exist_ok=True)
        self.log_handle = self.log_path.open("wb")
        command = [
            "java",
            f"-Djava.io.tmpdir={self.java_tmp}",
            "-jar",
            str(JAR),
            "--daemon",
            "--config",
            str(self.config_path),
        ]
        self.process = subprocess.Popen(
            command,
            cwd=REPO_ROOT,
            env=self.env,
            stdout=self.log_handle,
            stderr=subprocess.STDOUT,
        )
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise BenchError(f"RIID exited during startup; see {self.log_path}")
            if self.socket_path.exists():
                probe = subprocess.run(
                    [
                        "curl",
                        "-sS",
                        "--max-time",
                        "2",
                        "--unix-socket",
                        str(self.socket_path),
                        "-o",
                        "/dev/null",
                        "-w",
                        "%{http_code}",
                        "http://localhost/pull",
                    ],
                    capture_output=True,
                    text=True,
                    check=False,
                )
                if probe.returncode == 0 and probe.stdout.strip() in {"400", "404", "405"}:
                    return
            time.sleep(0.25)
        raise BenchError(f"RIID did not become ready; see {self.log_path}")

    def stop(self) -> None:
        if self.process is not None and self.process.poll() is None:
            self.process.send_signal(signal.SIGTERM)
            try:
                self.process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=30)
        self.process = None
        self.socket_path.unlink(missing_ok=True)
        if self.log_handle is not None:
            self.log_handle.close()
            self.log_handle = None


def post_riid_pull(socket_path: Path, repository: str, digest: str, trace_id: str) -> tuple[int, int, str]:
    body = json.dumps({"repository": repository, "reference": digest, "runtimeId": "podman"})
    command = [
        "curl",
        "-sS",
        "--max-time",
        "1800",
        "--unix-socket",
        str(socket_path),
        "-X",
        "POST",
        "http://localhost/pull",
        "-H",
        "Content-Type: application/json",
        "-H",
        f"X-Trace-Id: {trace_id}",
        "-w",
        "\n%{http_code}",
        "-d",
        body,
    ]
    started = time.monotonic()
    process = subprocess.run(command, capture_output=True, text=True, timeout=1860, check=False)
    wall_ms = int((time.monotonic() - started) * 1000)
    status = process.stdout.strip().splitlines()[-1] if process.stdout.strip() else ""
    exit_code = process.returncode if process.returncode else (0 if status == "200" else 1)
    error = process.stderr.strip() or ("" if exit_code == 0 else process.stdout.strip()[:500])
    return wall_ms, exit_code, error


def empty_row(run_id: str, iteration: int, phase: str, arm: Arm, variant: dict[str, Any]) -> dict[str, Any]:
    return {
        "run_id": run_id,
        "iteration": iteration,
        "phase": phase,
        "arm": arm.name,
        "codec": arm.codec,
        "path": arm.path,
        "artifact_digest": variant["manifest_digest"],
        "layer_bytes": variant["layer_bytes"],
        "wall_ms": "-",
        "t_dl_end_ms": "-",
        "handoff_layout_ms": "-",
        "handoff_import_ms": "-",
        "handoff_ms": "-",
        "sources": "-",
        "smoke": "-",
        "exit_code": 1,
        "error": "sample_not_started",
        "daemon_log": "-",
    }


def run_sample(
    run_id: str,
    run_dir: Path,
    work_dir: Path,
    iteration: int,
    phase: str,
    arm: Arm,
    artifacts: dict[str, Any],
    registry: Registry,
    smoke_command: list[str],
) -> dict[str, Any]:
    variant = artifacts["variants"][arm.codec]
    row = empty_row(run_id, iteration, phase, arm, variant)
    sample_name = f"{phase}-{iteration:02d}-{arm.name}"
    sample_root = Path(tempfile.mkdtemp(prefix=sample_name + "-", dir=work_dir))
    env = isolated_podman_env(sample_root)
    repository = artifacts["repository"]
    digest = variant["manifest_digest"]
    try:
        require_fresh_podman_store(env, sample_root / "graphroot")
        if arm.path == "podman":
            image = f"{registry.authority}/{repository}@{digest}"
            started = time.monotonic()
            process = subprocess.run(
                ["podman", "pull", "--quiet", f"--tls-verify={'true' if registry.tls_verify else 'false'}", image],
                capture_output=True,
                text=True,
                timeout=1800,
                env=env,
                check=False,
            )
            row["wall_ms"] = int((time.monotonic() - started) * 1000)
            row["exit_code"] = process.returncode
            row["error"] = process.stderr.strip().replace("\t", " ").replace("\n", " ")[:500]
            smoke_image = image
        else:
            socket_path = Path("/tmp") / f"riid-z-{uuid.uuid4().hex[:16]}.sock"
            config_path = sample_root / "config.yaml"
            log_path = run_dir / f"{sample_name}.daemon.log"
            render_config(config_path, registry, socket_path)
            daemon = RiidDaemon(config_path, socket_path, log_path, env, sample_root / "java-tmp")
            try:
                daemon.start()
                trace_id = f"agent97-{run_id}-{iteration}-{arm.name}-{uuid.uuid4().hex[:8]}"
                wall_ms, exit_code, error = post_riid_pull(socket_path, repository, digest, trace_id)
            finally:
                daemon.stop()
            timings = parse_trace(log_path, trace_id)
            row.update(
                {
                    "wall_ms": wall_ms,
                    "t_dl_end_ms": timings.t_dl_end_ms if timings.t_dl_end_ms is not None else "-",
                    "handoff_layout_ms": timings.handoff_layout_ms if timings.handoff_layout_ms is not None else "-",
                    "handoff_import_ms": timings.handoff_import_ms if timings.handoff_import_ms is not None else "-",
                    "handoff_ms": timings.handoff_ms if timings.handoff_ms is not None else "-",
                    "sources": sources_str(timings.sources),
                    "exit_code": exit_code,
                    "error": error.replace("\t", " ").replace("\n", " ")[:500],
                    "daemon_log": str(log_path.relative_to(REPO_ROOT)),
                }
            )
            if timings.errors and not row["error"]:
                row["error"] = f"trace_contains_{timings.errors}_errors"
                row["exit_code"] = 1
            smoke_image = f"localhost/{repository}"
        if row["exit_code"] == 0:
            verify = subprocess.run(
                ["podman", "image", "exists", smoke_image],
                capture_output=True,
                text=True,
                timeout=60,
                env=env,
                check=False,
            )
            if verify.returncode != 0:
                row["exit_code"] = verify.returncode or 1
                row["error"] = f"imported image not found: {smoke_image}"
            elif smoke_command:
                smoke = subprocess.run(
                    ["podman", "run", "--rm", smoke_image, *smoke_command],
                    capture_output=True,
                    text=True,
                    timeout=300,
                    env=env,
                    check=False,
                )
                output = (smoke.stdout or smoke.stderr).strip().replace("\t", " ").replace("\n", " ")[:200]
                row["smoke"] = output or f"exit={smoke.returncode}"
                if smoke.returncode != 0:
                    row["exit_code"] = smoke.returncode or 1
                    row["error"] = f"smoke command failed: {row['smoke']}"
    except (OSError, subprocess.TimeoutExpired, BenchError) as exc:
        row["error"] = str(exc).replace("\t", " ").replace("\n", " ")[:500]
        row["exit_code"] = 1
    finally:
        cleanup_error = cleanup_isolated_podman(env, sample_root)
        if cleanup_error:
            previous = row["error"]
            row["error"] = cleanup_error if previous in {"", "-", "sample_not_started"} else f"{previous}; {cleanup_error}"
            row["exit_code"] = 1
    if row["exit_code"] == 0:
        row["error"] = "-"
    return row


def sample_order(round_index: int) -> tuple[Arm, ...]:
    # Four rotations spread first/last position and registry page-cache effects.
    shift = round_index % len(ARMS)
    return ARMS[shift:] + ARMS[:shift]


def write_results(path: Path, metadata: dict[str, Any], rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        for key, value in metadata.items():
            handle.write(f"# {key}: {value}\n")
        writer = csv.DictWriter(handle, fieldnames=RESULT_COLUMNS, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def print_summary(path: Path, rows: list[dict[str, Any]]) -> None:
    print(f"\n[summary] {path}")
    medians: dict[str, float] = {}
    for arm in ARMS:
        clean = [
            int(row["wall_ms"])
            for row in rows
            if row["phase"] == "measure" and row["arm"] == arm.name and row["exit_code"] == 0
        ]
        if not clean:
            print(f"  {arm.name:20s} no successful measured samples")
            continue
        medians[arm.name] = statistics.median(clean)
        print(f"  {arm.name:20s} N={len(clean)} wall median={medians[arm.name]:.0f} ms samples={clean}")
    for label, gzip_arm, zstd_arm in (
        ("direct Podman", "podman-gzip", "podman-zstd"),
        ("real RIID -> Podman", "riid-podman-gzip", "riid-podman-zstd"),
    ):
        if gzip_arm in medians and zstd_arm in medians:
            delta = (medians[zstd_arm] / medians[gzip_arm] - 1) * 100
            print(f"  {label:20s} zstd vs gzip: {delta:+.1f}% wall time")


def benchmark(args: argparse.Namespace) -> Path:
    if args.iterations < 1 or args.warmups < 0:
        raise BenchError("iterations must be >= 1 and warmups >= 0")
    for binary in ("podman", "curl", "java"):
        if shutil.which(binary) is None:
            raise BenchError(f"{binary} not found")
    if not JAR.is_file():
        raise BenchError(f"missing {JAR}; run ./gradlew shadowJar")
    artifacts, registry = load_artifacts(args.artifacts.resolve())
    run_id = utc_stamp()
    run_dir = RESULTS_DIR / f"zstd-{run_id}"
    work_dir = run_dir / "work"
    run_dir.mkdir(parents=True, exist_ok=False)
    work_dir.mkdir()
    rows: list[dict[str, Any]] = []
    total_rounds = args.warmups + args.iterations
    try:
        for round_index in range(total_rounds):
            phase = "warmup" if round_index < args.warmups else "measure"
            iteration = round_index if phase == "warmup" else round_index - args.warmups + 1
            for arm in sample_order(round_index):
                print(f"[run] {phase}={iteration} arm={arm.name}")
                row = run_sample(
                    run_id, run_dir, work_dir, iteration, phase, arm, artifacts, registry, args.smoke_command
                )
                rows.append(row)
                print(f"[run]   wall={row['wall_ms']}ms exit={row['exit_code']} sources={row['sources']} error={row['error']}")
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)

    result_path = RESULTS_DIR / f"zstd-{run_id}.tsv"
    metadata = {
        "run_id": run_id,
        "commit": git_commit(),
        "artifacts": args.artifacts.resolve(),
        "registry": f"{registry.scheme}://{registry.authority}",
        "repository": artifacts["repository"],
        "podman": tool_version("podman"),
        "java": tool_version("java", "-version"),
        "kernel": tool_version("uname", "-sr"),
        "cpu_count": os.cpu_count() or "unknown",
        "memory_mib": int(os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") / (1024 * 1024)),
        "storage_driver": "overlay (fresh graphroot per sample)",
        "riid_jar_sha256": file_sha256(JAR),
        "benchmark_sha256": file_sha256(Path(__file__)),
    }
    write_results(result_path, metadata, rows)
    print_summary(result_path, rows)
    failures = [row for row in rows if row["phase"] == "measure" and row["exit_code"] != 0]
    if failures:
        raise BenchError(f"{len(failures)} measured samples failed; results kept in {result_path}")
    return result_path


def check(args: argparse.Namespace) -> None:
    problems = []
    for binary in ("podman", "curl", "java"):
        if shutil.which(binary) is None:
            problems.append(f"{binary} not found")
    if not JAR.is_file():
        problems.append(f"missing {JAR}; run ./gradlew shadowJar")
    if not CONFIG_TEMPLATE.is_file():
        problems.append(f"missing {CONFIG_TEMPLATE}")
    if not problems:
        artifacts, registry = load_artifacts(args.artifacts.resolve())
        print(f"artifacts: {args.artifacts.resolve()}")
        print(f"registry:  {registry.scheme}://{registry.authority}/{artifacts['repository']}")
        print(f"podman:    {tool_version('podman')}")
        print(f"java:      {tool_version('java', '-version')}")
        print("artifact gate: PASS")
        return
    raise BenchError("; ".join(problems))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    prepare_parser = subparsers.add_parser("prepare", help="publish and verify an equivalent gzip/zstd pair")
    prepare_parser.add_argument("--source", required=True, help="local Podman image used as the common source")
    prepare_parser.add_argument("--registry", default="http://127.0.0.1:5000")
    prepare_parser.add_argument("--repository", default="riid/agent97-zstd")
    prepare_parser.add_argument("--gzip-tag", default="gzip")
    prepare_parser.add_argument("--zstd-tag", default="zstd")
    prepare_parser.add_argument("--gzip-level", type=int, default=6)
    prepare_parser.add_argument("--zstd-level", type=int, default=3)
    prepare_parser.add_argument("--output", type=Path, default=DEFAULT_ARTIFACTS)

    run_parser = subparsers.add_parser("run", help="run four real Podman/RIID arms")
    run_parser.add_argument("--artifacts", type=Path, default=DEFAULT_ARTIFACTS)
    run_parser.add_argument("--iterations", type=int, required=True)
    run_parser.add_argument("--warmups", type=int, default=1)
    run_parser.add_argument(
        "--smoke-command",
        type=shlex.split,
        default=[],
        help="command executed in every imported image after (outside) the timed interval",
    )

    check_parser = subparsers.add_parser("check", help="validate the toolchain and immutable artifact pair")
    check_parser.add_argument("--artifacts", type=Path, default=DEFAULT_ARTIFACTS)

    args = parser.parse_args()
    try:
        if args.command == "prepare":
            prepare(args)
        elif args.command == "run":
            benchmark(args)
        else:
            check(args)
    except BenchError as exc:
        sys.stderr.write(f"zstd-bench: {exc}\n")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
