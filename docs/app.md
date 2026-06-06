# App **module** (CLI + wiring)

## Purpose
CLI and dependency wiring layer for loading container images: parse flags, validate user input, invoke `ImageLoadService`, and return exit codes.

## Architecture
- `CliApplication` (JAR `Main-Class`): `main`, parses args, prints help/usage, validates options (repo/runtime/auth/cert/key/CA), exit codes and stdout/stderr.
- `CliParser` (inside `CliApplication`): pure argument parser → DTO `CliOptions`.
- `ImageLoadServiceFactory`: reads YAML (`config/config.yaml`), assembles dependencies (RegistryClient, Dispatcher, P2P stub, RuntimeRegistry).
- `ImageLoadService`: façade that downloads, assembles OCI, and imports into runtime; no CLI logic.
- `RuntimeRegistry`: registry of runtime adapters (`podman`, `porto`), throws a clear error for unknown runtime.
- `RiidEnv`: helpers for env-based launching without CLI.

## Policy: ImageId vs ImageRef
- `ImageId` (app-level): full identity with registry + name + tag/digest; used in App/OCI/Runtime flows.
- `ImageRef` (dispatcher-level): repository + tag/digest only; used inside dispatcher/registry fetch logic.
- Boundary rule: CLI/ENV/API build `ImageId` once; dispatcher takes `ImageRef` derived from `ImageId`.

## Policy: HostFilesystem
- All filesystem operations outside `riid.app.fs` go through `HostFilesystem`.
- `NioHostFilesystem` is the default implementation; it wraps `java.nio.file.Files`.

## CLI flags
Required:
- `--repo` — repository (e.g., `library/busybox`)
- `--runtime` — runtime id (`podman`, `porto`)

Reference:
- `--tag` / `--ref` — tag (default: `latest`)
- `--digest` — `sha256:...` (overrides tag/ref)

Config:
- `--config` — path to YAML config
- If `--config` is omitted, CLI first checks default path `config/config.yaml`.
- If default path is missing, CLI uses built-in defaults (Docker Hub endpoint + default HTTP/auth/dispatcher settings).
- If `--config` is explicitly provided and file is missing/invalid, CLI fails with an error.

Basic auth (password options are mutually exclusive):
- `--username <user>`
- `--password <pwd>` | `--password-env <VAR>` | `--password-file <path>`

TLS (wired to HTTP client):
- `--cert-path <path>`
- `--key-path <path>`
- `--ca-path <path>`
- `cert`/`key` are used as a client certificate pair for mTLS.
- `ca` is used as custom trust store for server certificate validation.

Other:
- `--help` — show usage

## Daemon mode (`--daemon`)
Long-lived process: embedded **Jetty** serves **HTTP over a Unix domain socket** (control plane) and **TCP** for metrics.

- **CLI:** `java -jar riid.jar --daemon [--config <path>]` — same auth/TLS flags as one-shot mode when you pass `--config`. In daemon mode **`--repo` / `--runtime` are not required** (pulls are driven by HTTP clients).
- **Config:** optional `app.daemon` in YAML (socket path, metrics bind, concurrency, timeouts). See **Optional `app.daemon`** in [config.md](config.md).
- **Control plane (UDS):** `POST /pull` with JSON body `{ "repository", "reference", "runtimeId" }` — success `200` with `status`, `imagePath`; errors use JSON `code` / `message` and HTTP status (4xx/5xx) per handler policy.
- **Metrics (TCP):** `GET /metrics` on `metricsHost:metricsPort` serves **Prometheus text exposition** (`text/plain; version=0.0.4`) from a **Micrometer `PrometheusMeterRegistry`**. After pulls, scrapers (e.g. VictoriaMetrics vmagent, Prometheus) poll this URL. Main exported name prefixes include:
  - **`riid_daemon_pull_*` (HTTP handler timer)** — one observation per finished `POST /pull`, from handler entry through loader completion. Micrometer name `riid.daemon.pull` becomes Prometheus families such as `riid_daemon_pull_seconds_bucket` / `_count` / `_sum` with percentile buckets enabled. **Tags (low cardinality):** `status` (numeric HTTP code, e.g. `200`, `504`), `status_class` (`2xx` … `5xx`, `other`), `code` — same idea as JSON `code` / `success` for 200 (`success`, `timeout`, `overloaded`, `unknown_runtime`, etc.). Use this series for request-rate and error breakdown **by HTTP layer**; latency here includes JSON/body handling, not just registry work.
  - **`riid_image_load_*` (loader pipeline timer)** — time inside `ImageLoader.load` only (download / cache / import), independent of HTTP framing. Name `riid.image.load` → `riid_image_load_seconds_*`. **Tags:** `result` is `success`, `error`, or `timeout`. **`category`** on the timer is the tar **size bucket** on success when tar size is known (binary MiB bands: `0_5_mib`, `5_10_mib`, … `gt_5120_mib`), or `unknown` when the loader reports success but tar size is not set (`-1`), or `n_a` for failures/timeouts. **`sort_idx`** is a zero-padded enum order (`00`…`11`) for stable Grafana ordering alongside `category`. Dashboards often aggregate histograms with PromQL `histogram_quantile` on `riid_image_load_seconds_bucket`.
  - **Tar size / throughput (success path with known tar size only)** — separate from the timer histograms, for counts and SLO-style throughput:
    - `riid_image_load_tar_size_category_total` — **Counter** per `category` and matching **`sort_idx`**; increments once per successful load with a known tar byte size.
    - `riid_image_load_tar_size_by_category_*` — **DistributionSummary** (`_count` / `_sum` per `category`, **`sort_idx`**) recording raw tar bytes per bucket (useful for mean tar size per cohort).
    - `riid_image_load_tar_size_bytes_*` — global summary of tar sizes (all successful loads with known size).
    - `riid_image_provide_throughput_bps_*` — effective bytes/sec (tar size ÷ pipeline duration); histogram-style buckets when exported.
    - `riid_image_provide_throughput_slo_bps_*` — same, but only when tar size ≥ **10 MiB** (SLO cohort in code).  
    Grafana JSON for these names lives under `config/metrics/grafana/dashboards/`; a runnable VM + vmagent + Grafana layout is described in `config/metrics/README.md`.
  - **Dispatcher layer source (when using Micrometer in `ImageLoadingFacade`):** `riid_dispatcher_layer_fetches_total` — counter of **layer resolutions** tagged `source` (`cache`, `p2p`, `registry`); `riid_dispatcher_layer_bytes_total` — **payload bytes** attributed to the same `source` (for volume / `increase()` in PromQL, e.g. **`RIID main`** panel *Layer bytes by source (trailing 6h)*).
- **Bind / firewall:** `metricsHost` controls the listen address (default `0.0.0.0` = all interfaces). Use `127.0.0.1` if the scrape agent runs on the same host and you want no remote access. Open the chosen **TCP port** in the host firewall for whatever runs the scraper; if the scraper is remote, it must reach `metricsHost:metricsPort` on the network path you configure in `vmagent-scrape.yaml` (or equivalent).

### Example: `curl` over the Unix socket
Host in the URL is ignored by libcurl; use a dummy host:

```bash
SOCKET=/run/riid/riid.sock   # must match app.daemon.unixSocketPath (or default)

curl --unix-socket "$SOCKET" -sS -X POST "http://localhost/pull" \
  -H 'Content-Type: application/json' \
  -d '{"repository":"library/busybox","reference":"latest","runtimeId":"podman"}'
```

### Example: `systemd` unit
Ensure the socket parent directory exists (or use `RuntimeDirectory=` and a path under `/run/...`). Example:

```ini
[Unit]
Description=RIID image pull daemon
After=network-online.target

[Service]
Type=simple
RuntimeDirectory=riid
ExecStart=/usr/bin/java -jar /opt/riid/riid.jar --daemon --config /etc/riid/config.yaml
Restart=on-failure
# If unixSocketPath is /run/riid/riid.sock, RuntimeDirectory=riid provides /run/riid

[Install]
WantedBy=multi-user.target
```

## Logging

Structured **JSON logs** (stdout/stderr) with `trace_id`, step `event`s, and masking: see [logs-policy.md](logs-policy.md). Entry point sets request MDC and emits `request.start` / `request.finish`; `ImageLoadingFacade` and `OciArchiveBuilder` emit manifest/archive/import milestones.

## Exit codes
- `0` — success
- `64` — usage errors (missing/unknown flags, required options not provided)
- `65` — unknown runtime
- `1` — execution failure (download/import)

## Examples
```bash
# Tag
java -jar riid.jar --repo library/busybox --tag latest --runtime podman

# Digest + basic auth from env
RIID_PASS=secret \
java -jar riid.jar --repo library/busybox --digest sha256:abc... --runtime porto \
  --username user --password-env RIID_PASS

# Custom config
java -jar riid.jar --config ./config/config.yaml --repo registry.example.com/app --tag v1 --runtime podman

# Daemon (see "Daemon mode" above)
java -jar riid.jar --daemon --config /etc/riid/config.yaml
```

## Tests
```bash
./gradlew testApp
# Linux: real UDS + curl smoke for daemon IPC; mocked pull + GET /metrics scrape checks
./gradlew moduledTest -PincludeLocal --tests riid.app.daemon.DaemonPullUnixSocketTest
./gradlew moduledTest -PincludeLocal --tests riid.app.daemon.DaemonPullMetricsEndpointTest
```
Coverage includes:
- `CliApplicationTest` for negative cases (no args, unknown runtime) and happy-path argument propagation.
- `ImageLoadServiceFactoryTest` for building the service from a minimal YAML config.
- Password source handling:
  - positive path for `--password-file` (non-empty file),
  - negative paths for missing env var / empty password file / conflicting password sources.