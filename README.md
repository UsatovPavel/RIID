# RIID CLI quick start

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

### Porto runtime note
Porto runtime adapters are wired the same way via `--runtime porto`; ensure Porto is installed and accessible on the host. The CLI flow is identical; only the runtime id changes.

### Integration / manual checks
- `./gradlew test` (unit + integration). Live/e2e tests that hit public registries may require network; disable/skip if offline.
- For Podman integration end-to-end: run local registry as above, then `./gradlew test --tests "riid.integration.runtime_app.CliPodmanIntegrationTest"` (requires podman and network for base images).
- For Porto: use analogous CLI invocation with `--runtime porto` after ensuring runtime availability.
- Registry endpoint for live tests is configurable via system properties:
  - `riid.test.registry.scheme` (default: `https`)
  - `riid.test.registry.host` (default: `registry-1.docker.io`)
  - `riid.test.registry.port` (default: `-1`)

# Dragonfly
Pull container images 
```bash
docker pull dragonflyoss/dfdaemon:latest
docker pull dragonflyoss/manager:latest
docker pull dragonflyoss/scheduler:latest
```


## Supported runtimes
- podman
- docker
If you need another inherit from runtime adapter in runtime module

## P2P (Dragonfly)
Currently supported via `dfget` CLI. 

## Module docs:
For more info see docs/*.md