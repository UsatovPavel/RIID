# Runtime Adapters

## Purpose
 Adapt a prepared OCI archive into a specific container runtime (docker/podman/porto). Each adapter is responsible for translating the universal OCI layout into the runtime’s expected import format and invoking the runtime CLI.

## Core types
 `RuntimeAdapter` (interface), implementations `DockerRuntimeAdapter`, `PodmanRuntimeAdapter`, `PortoRuntimeAdapter`, registry of adapters in `RuntimeRegistry`.
## Responsibilities:
  - Accept OCI archive from `ImageLoadService`.
  - Perform runtime-specific import (e.g., `docker load`, `podman load`, `portoctl`).
  - For Docker only: rewrite OCI archive into docker-save format (`manifest.json` + `repositories`) before `docker load`; no network calls.
  - Validate input file existence and surface failures as IO/Unchecked exceptions.
## CLI/interop:
  - Docker: uses host `docker` binary; requires archive readable on host filesystem.
  - Podman: uses `podman load` (WSL-friendly).
  - Porto: uses `portoctl` (have troubles with develop portodshim in local wsl).
## Architecture constraints:
  - Runtime-agnostic flow up to adapter boundary (`ImageLoadService` produces OCI once).
  - No downloading inside adapters; they only consume already-fetched/cached blobs.
  - Adapters are registered by runtime id (`docker`, `podman`, `porto`) via `ImageLoadServiceFactory.defaultRuntimes()`.
## Tests:
  - Unit: `runtime/RuntimeAdaptersTest` (missing file errors, non-zero exit handling, success paths for docker/podman/porto with fake processes).
  - Unit (porto): `runtime/PortoRuntimeAdapterTest` (missing file, optional local `PORTO_LAYER_TAR` happy-path through `portoctl`).
  - Integration: `integration/runtime_app` for Docker: (pull alpine:edge from Docker Hub via app, verify `docker images`; one-shot load+run with runtime registry wiring).
  for Podman also