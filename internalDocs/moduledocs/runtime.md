## Runtime module

### Purpose
Import prepared OCI artifacts into a target container runtime via runtime-specific adapters.

### Responsibilities
- Define the `RuntimeAdapter` contract and map runtime ids to implementations.
- Execute runtime CLI commands and handle output limits.
- Convert OCI layout to runtime-specific formats when needed (Docker save format).

### Core types
- `RuntimeAdapter` (interface) and adapters: `PodmanRuntimeAdapter`, `PortoRuntimeAdapter`, `DockerRuntimeAdapter`, `ContainerdRuntimeAdapter`.
- `RuntimeAdapter.runtimeId()` returns `RuntimeId` (enum: `PODMAN`/`PORTO`/`DOCKER`/`CONTAINERD`), not a raw `String`. `RuntimeId.value()` gives the lowercase id string (`toString()` matches), `RuntimeId.from(String)` parses/validates one. `RuntimeRegistry`/CLI/daemon HTTP still key and accept plain `String` (`--runtime <name>`, JSON `runtimeId`) — there is no config-file field for runtime selection, so the enum only changed the adapter/wiring layer, not the external contract.
- `BoundedCommandExecution` and `OutputConfig` for process output capture/limits.
- `RuntimeConfig` to configure `runtime.output` settings.

### Wiring
- Runtimes are registered in `RuntimeRegistry` and used by `ImageLoadingFacade`.
- `ImageLoadingFacade.defaultRuntimes()` and `createFromConfig(...)` register `podman`, `porto`, `docker`, `containerd`.

### containerd (`ctr images import`)
- `ctr images import <path>` accepts an OCI v1 archive (the same tar RIID builds for Podman/Docker/Porto) directly — no docker-save-style rewrite needed (unlike `DockerRuntimeAdapter`).
- containerd's OCI v1 importer keeps an existing `org.opencontainers.image.ref.name` annotation untouched (source: `core/images/archive/importer.go` in `containerd/containerd`), so RIID's archive (annotation = `name:tag`, e.g. `library/busybox:latest`, no registry host) imports without `--base-name`.
- `prefersOciLayoutStreamImport()` is `true` (unlike Podman, which switched to `-i <path>`): Podman's win skips its own stdin→tempfile copy, but `ctr images import` has no such copy to skip either way, so streaming (`tar -cf - -C layout . | ctr images import -`) still avoids a real disk write.
- `ctr` talks to `containerd` over a Unix socket (default `/run/containerd/containerd.sock`), root-owned by default — same operational constraint as Porto's `portod.socket` (see CI job below, and `PortoRuntimeAdapterIntegrationTest`'s note on running as root).
- Constructor `ContainerdRuntimeAdapter(String ctrCmd, String namespace, String address, String snapshotter)` allows overriding the `ctr` binary and `-a`/`-n`/`--snapshotter` flags for tests/non-default setups; the no-arg constructor uses `ctr` with containerd's own defaults (namespace `default`, address `/run/containerd/containerd.sock`).
- **Content-addressed import**: `ctr` verifies each blob's digest, so `OciArchiveBuilder` must name the manifest blob by its own actual SHA-256, not the registry's (fixed; see `OciArchiveBuilderTest`).

### Config
- `runtime.output` controls stdout/stderr capture and max bytes (validated in `ConfigValidator`).
- Defaults are applied via `RuntimeConfig.outputConfigOrDefault()`.

### Tests
- Moduled: `src/test/moduled/java/riid/runtime/RuntimeAdaptersTest.java` (mocked `runCommand`, no real binaries needed).
- Integration: `src/test/integration/java/riid/integration/runtime_app/PodmanRuntimeAdapterIntegrationTest.java`,
  `DockerRuntimeAdapterIntegrationTest.java`, `ContainerdRuntimeAdapterIntegrationTest.java` (all tagged `local`/`filesystem`; containerd test needs a reachable `containerd.sock`, run as root — see CI job `containerd-integration-test`).
  Containerd has two tests: `downloadsImageAndImportsIntoContainerd` (listing-only, `ctr images ls`) and `loadsAlpineEdgeAndRuns` (`ctr run --rm <image> <id> true`, parity with Podman/Docker actually running the container, not just listing it).

### CI
- `containerd-integration-test` (`.github/workflows/ci.yml`) runs as root on `ubuntu-latest` (containerd.io preinstalled); apt-based jobs (`linux-integration-test`/`porto-integration-test`) got a 15m timeout + apt-lock hardening after a runner `apt-daily.timer` hang.

