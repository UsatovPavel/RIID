# RIID — Runtime-Independent Image Downloader

**RIID** decouples container image distribution from the container engine.
It pulls an image from a registry (or via P2P), assembles a valid OCI artifact, and imports it
into the target runtime—without that runtime knowing anything about networking or P2P peers.

Developed as a research project at **HSE University in Saint Petersburg** — see [NOTICE](NOTICE).
Licensed under the [Apache License 2.0](LICENSE).

---

## Why RIID

Containerization is the de-facto standard for deploying applications at cloud scale.
Image *distribution*—delivering multi-gigabyte layers to thousands of nodes—is consistently the bottleneck. Existing P2P solutions (Dragonfly, Kraken) are often tightly coupled to a single container engine (Docker or Podman), making engine migrations painful.

**RIID separates the concerns:**

- A single downloader binary handles registry auth, P2P, and local caching.
- Runtime adapters are ~300-line implementations of a single interface.
- Switching from Podman to Docker, Porto, or any OCI-compatible engine requires only a new adapter.

- Verified by 100+ images from 2 Kb to 4.8 GB both in p2p and registry scenarios (see *benchmarks*)
All data from external sources (registry, P2P) is integrity-verified (SHA-256).

---

## Operation modes

| Mode | Description |
|------|-------------|
| **CLI** | One-shot pull via `java -jar riid.jar --repo … --runtime podman` |
| **Daemon** | Long-lived process; control via Unix socket `POST /pull` (JSON); Prometheus metrics on TCP |

---

## Download sources

RIID selects the best available source for each image layer:

| Source | Implementation |
|--------|---------------|
| **Registry client** | Custom Docker Registry API v2 client with auth, TLS/mTLS, token cache |
| **P2P (Dragonfly)** | [`java-dragonfly-image-puller`](https://github.com/UsatovPavel/java-dragonfly-image-puller) — **Our** library for gRPC to dfdaemon |
| **Local cache** | Content-addressable OCI layer store; avoids any network round-trip on re-pull |

The **Dispatcher** (orchestrator) decides the source, handles fallback, and writes results back to the cache.

---

## Benchmarks

### Registry client — direct pull performance (30 images, Yandex server)

Registry client performance was measured against `podman pull`.
Results are presented in the interactive chart:

**[Registry client vs Podman — pull time comparison](docs/images/benchmark-visualization.html)**

### P2P cluster benchmark — dataset A (91 images, Selectel cluster)

**Recreate scenario** (all 10 RIID pods pull simultaneously):

| Metric | Formula | RIID+Dragonfly | Podman (baseline) | Ratio |
|--------|---------|---------------|-------------------|-------|
| **Sum of means** | Σt̄ᵣ / Σt̄ₚ | 875 sec | 1073 sec | **0.82×** (18% faster) |
| **Sum of aggregates** (wall-clock) | ΣTᵃᵍᵍ / ΣTᵃᵍᵍ | 969 sec | 1108 sec | **0.88×** (12% faster) |
| **Registry TX** (egress) | — | **11.6 GiB** | 112.6 GiB | **−89.7%** |

**[Interactive scatter: RIID P2P vs Podman — recreate scenario](docs/images/riid-p2p-vs-podman-scatter-recreate.html)**

**Rolling scenario** (concurrency-limited sequential pulls) showed comparable download speed with **−82.6% registry traffic** reduction (19.7 GiB vs 112.6 GiB). See **[deploy/k8s/README.md](deploy/k8s/README.md)** for detailed methodology and rolling scenario results.

Full cluster setup, test methodology: **[deploy/k8s/README.md](deploy/k8s/README.md)**

<img width="1000" height="560" alt="image" src="https://github.com/user-attachments/assets/008259f0-1659-4979-9270-9b3f7e72a659" />

## Stack
Java 23  
Gradlew 9.2  
Dragonfly 2.2  
### Observability stack
Daemon exposes Prometheus metrics (`/metrics` on TCP); dashboards for VictoriaMetrics + Grafana are in [`config/metrics/`](config/metrics/README.md).
See [`graphs from cluster benchmark`](docs/images/graphs-dataset-a.png)

---

## Supported runtimes

| Runtime | Notes |
|---------|-------|
| **podman** | Full support; OCI layout stream import (optimized path) |
| **docker** | Supported via archive materialization |
| **porto** | Requires `portoctl` + running `portod`; imports via `portoctl layer -I` |

To add a new runtime: implement the `RuntimeAdapter` interface (~300 lines).

## Architecture

RIID is modular. Each layer is independently testable.  

<img width="591" height="311" alt="RIID diagram (1)" src="https://github.com/user-attachments/assets/f58e0e84-dc0e-43d9-a02e-38ebdc9d54ca" />


| Module | Docs | Responsibility |
|--------|------|---------------|
| **App** (CLI + wiring) | [docs/app.md](docs/app.md) | Arg parsing, dependency assembly, daemon/CLI entrypoint |
| **Registry Client** | [docs/client.md](docs/client.md) | Docker API v2, auth, manifests, blob fetch |
| **Dispatcher** |  | Source strategy, parallelism, fallback, cache writes |
| **P2P Layer** |  | Dragonfly gRPC integration via `java-dragonfly-image-puller` |
| **Cache** | | Content-addressable OCI layer store, token cache |
| **Runtime Adapter** | | OCI artifact import into Podman / Docker / Porto |
| **Tests** | | 4 categories: moduled, e2e, performance, fuzzing, each in separate module|

---

## Quick start

```bash
# 1. Set GitHub Packages token (for java-dragonfly-image-puller)
# in ~/.gradle/gradle.properties:
#   gpr.user=YOUR_GITHUB_USERNAME
#   gpr.key=GITHUB_PACKAGES_READ_TOKEN

# 2. Build
./gradlew clean shadowJar

# 3. Pull an image into Podman
java -jar build/libs/riid.jar \
  --config ./config/config.yaml \
  --repo library/busybox \
  --tag latest \
  --runtime podman
---
