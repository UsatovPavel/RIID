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
- Like Podman, `prefersOciLayoutStreamImport()` is `true`: `tar -cf - -C layout . | ctr images import -` (stdin), avoiding writing the full oci-archive tar to disk.
- `ctr` talks to `containerd` over a Unix socket (default `/run/containerd/containerd.sock`), root-owned by default — same operational constraint as Porto's `portod.socket` (see CI job below, and `PortoRuntimeAdapterIntegrationTest`'s note on running as root).
- Constructor `ContainerdRuntimeAdapter(String ctrCmd, String namespace, String address, String snapshotter)` allows overriding the `ctr` binary and `-a`/`-n`/`--snapshotter` flags for tests/non-default setups; the no-arg constructor uses `ctr` with containerd's own defaults (namespace `default`, address `/run/containerd/containerd.sock`).

### Config
- `runtime.output` controls stdout/stderr capture and max bytes (validated in `ConfigValidator`).
- Defaults are applied via `RuntimeConfig.outputConfigOrDefault()`.

### Tests
- Moduled: `src/test/moduled/java/riid/runtime/RuntimeAdaptersTest.java` (mocked `runCommand`, no real binaries needed).
- Integration: `src/test/integration/java/riid/integration/runtime_app/PodmanRuntimeAdapterIntegrationTest.java`,
  `DockerRuntimeAdapterIntegrationTest.java`, `ContainerdRuntimeAdapterIntegrationTest.java` (all tagged `local`/`filesystem`; containerd test needs a reachable `containerd.sock`, run as root — see CI job `containerd-integration-test`).

