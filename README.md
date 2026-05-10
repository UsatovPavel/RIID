# RIID Daemon quick start

Minimal steps to run the client end-to-end from the Shadow JAR.
## Requirements
For pull libraries you need pass token in env, for example in  ~/.gradle/gradle.properties: 
```
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=GITHUB_PACKAGES_READ_TOKEN
```
### Stack:
Java 23
Gradlew 9.2 
Dragonfly 2.2

./gradlew

## Build
```bash
./gradlew clean shadowJar
```

The JAR will be at `build/libs/riid.jar` (manifest points to `riid.app.CliApplication`).

## Run (E2E smoke)
Pull BusyBox into Podman runtime using the default config:
```bash
java -jar build/libs/riid.jar \
  --config ./config/config.yaml \
  --repo library/busybox \
  --tag latest \
  --runtime podman
```

With basic auth from env and a custom registry:
```bash
RIID_PASS=secret \
java -jar build/libs/riid.jar \
  --config ./config/config.yaml \
  --repo registry.example.com/app \
  --digest sha256:abc... \
  --runtime porto \
  --username user --password-env RIID_PASS
```

TLS/mTLS options (wired to HTTP client):
- `--cert-path` + `--key-path` for client certificate authentication (mTLS).
- `--ca-path` for custom CA trust.
- Runtime security failures are emitted in safe unified format:
  - `SECURITY:TLS:<kind>: <safe_message>`
  - `SECURITY:AUTH:<kind>: <safe_message>`

## Tests
```bash
./gradlew testApp
```

## Daemon mode and Grafana

**Daemon** (`--daemon`): a long-lived process with **Jetty** — **Unix socket** control plane (`POST /pull` with JSON) and a **TCP** **Prometheus scrape** endpoint (`GET /metrics` on `metricsHost:metricsPort`). Options and defaults: [`docs/config.md`](docs/config.md) (`app.daemon`); endpoints, metric names, bind/firewall notes: [`docs/app.md`](docs/app.md) (Daemon mode).

**Grafana / VictoriaMetrics (local example):** configs live under [`config/metrics/`](config/metrics/README.md). From the repo root with Make (bash): `make metrics-stack-create` starts VictoriaMetrics + vmagent + Grafana (Grafana on port **3000**, VM on **8428**); `make metrics-stack-update` restarts Grafana to pick up changed dashboard JSON; `make metrics-stack-down` removes the containers. See the root `Makefile` for smoke targets such as `make download_to_daemon_1MB` (expects daemon UDS at `/tmp/riid.sock` per your config).

**Daemon quick loop:** `make daemon-new` runs `clean shadowJar` then `make daemon` (registry auth via optional `config/.env` and `DOCKERHUB_*` as in the `Makefile`).

## Local registry + integration smoke

### Start local registry (docker/podman)
```bash
podman run --rm -d -p 5000:5000 --name riid-registry registry:2
# or: docker run --rm -d -p 5000:5000 --name riid-registry registry:2
```

Push a tiny image for testing (optional):
```bash
podman pull hello-world
podman tag hello-world localhost:5000/hello-world
podman push localhost:5000/hello-world
```

### Run CLI against local registry (podman runtime)
```bash
java -jar build/libs/riid.jar \
  --config ./config/config.yaml \
  --repo localhost:5000/hello-world \
  --tag latest \
  --runtime podman
```


### Integration / manual checks
- `./gradlew test` (unit + integration). Live/e2e tests that hit public registries may require network; disable/skip if offline.
- For Podman integration end-to-end: run local registry as above, then `./gradlew test --tests "riid.integration.runtime_app.CliPodmanIntegrationTest"` (requires podman and network for base images).
- For Porto integration tests locally: `./gradlew integrationTest --tests 'riid.integration.runtime_app.PortoRuntimeAdapterIntegrationTest'` (requires Porto + permissions as above).
- Registry endpoint for live tests is configurable via system properties:
  - `riid.test.registry.scheme` (default: `https`)
  - `riid.test.registry.host` (default: `registry-1.docker.io`)
  - `riid.test.registry.port` (default: `-1`)

# Dragonfly
For local Dragonfly environment use project scripts:
```bash
./scripts/ci-dragonfly-setup.sh
./scripts/minikube-dragonfly.sh 1
# or
make -C scripts minikube-delete-and-start
```

RIID P2P uses an external custom Java library `ru.hse:java-dragonfly-image-puller`
for Dragonfly gRPC communication (`dfdaemon` DownloadTask, API v2).


## Supported runtimes
- **podman**
- **docker**
- **porto** (`portoctl` / portod; see Porto runtime note above)

If you need another inherit from runtime adapter in runtime module

### Porto runtime note
Porto is wired the same way via `--runtime porto`. You need **`portoctl`** and a running **portod** on the host. Imports use `portoctl layer -I`; the OS user that runs RIID must be allowed by Porto to create layers (often root or a dedicated service account — see Porto docs for ACLs). 

## P2P (Dragonfly)
Implemented via Dragonfly gRPC through the external `java-dragonfly-image-puller` library.
`dfget` CLI is not used in RIID runtime flow.

## Module docs:
For more info see docs/*.md


## Kubernetes deployment

Manifests and **Make** targets are under **`deploy/k8s/`**. Set kubectl context via kubeconfig (default **`deploy/k8s/providers/cluster/Selectel/serverConfig.yaml`**; copy from **`serverConfig.example.yaml`** in that folder; override with **`CONFIG_FILE=…`** on each `make -C deploy/k8s/bootstrap …`).

Typical first-time bootstrap:

```bash
make -C deploy/k8s/bootstrap install-all
```
```.env
RIID_DOCKERHUB_USER=
RIID_DOCKERHUB_TOKEN=
RIID_SELECTEL_USER=
RIID_SELECTEL_TOKEN=
RIID_REGISTRY_PROVIDER=SELECTEL/DOCKERHUB
```
That wires storage labels (when applicable), Dragonfly, RIID DaemonSet, vmagent, pod tooling, and the monitoring observer chart. Put **`deploy/k8s/config/.env`** for registry secrets when needed — see **`deploy/k8s/config/.env.example`**. Full sequence, smoke pulls, and registry switching: **[deploy/k8s/README.md](deploy/k8s/README.md)**.