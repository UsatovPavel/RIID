# App **module** (CLI + wiring)

## Purpose
CLI and dependency wiring layer for loading container images: parse flags, validate user input, invoke `ImageLoadService`, and return exit codes.

## Architecture
- `RiidCli` (JAR entrypoint): minimal `main`, calls CLI, returns exit code only.
- `CliApplication`: parses args, prints help/usage, validates options (repo/runtime/auth/cert/key/CA), handles exit codes and stdout/stderr.
- `CliParser` (inside `CliApplication`): pure argument parser → DTO `CliOptions`.
- `ImageLoadServiceFactory`: reads YAML (`config.yaml`), assembles dependencies (RegistryClient, Dispatcher, P2P stub, RuntimeRegistry).
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
- `--config` — path to YAML config (default: `config.yaml`)

Basic auth (password options are mutually exclusive):
- `--username <user>`
- `--password <pwd>` | `--password-env <VAR>` | `--password-file <path>`

TLS (stub for now: only validates file existence, not yet used):
- `--cert-path <path>`
- `--key-path <path>`
- `--ca-path <path>`

Other:
- `--help` — show usage

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
java -jar riid.jar --config ./config.yaml --repo registry.example.com/app --tag v1 --runtime podman
```

## Tests
```bash
./gradlew testApp
```
Coverage includes:
- `CliApplicationTest` for negative cases (no args, unknown runtime) and happy-path argument propagation.
- `ImageLoadServiceFactoryTest` for building the service from a minimal YAML config.
