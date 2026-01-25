## Registry Client

### Config
- Registry endpoints via `RegistryEndpoint` (scheme/host/port/creds); list provided by external config/Orchestrator.
- HTTP client: `HttpClientConfig` (timeouts, idempotent GET retries, backoff, User-Agent, followRedirects=true для GHCR CDN 302/307).
- Range policy: `RangeConfig` (mode/partialValidation/fallbackToFullOn416) controls Range usage and validation.
- Cache: external `CacheAdapter` (optional); client writes to cache after download. Source choice (cache/P2P/registry) is up to Orchestrator.

### Supported operations
- `fetchManifest(repo, ref)` → `ManifestResult` (validates digest, handles manifest list/index).
- `fetchBlob(request, file)` → `BlobResult` (SHA256, size, Range/retries).
- `headBlob(repo, digest)` → Optional size.
- `fetchConfig(repo, manifest, file)` → blob by config.digest.
- `listTags(repo, n, last)` (HTTP to registry).

### Usage
```java
RegistryEndpoint endpoint = new RegistryEndpoint("https", "registry-1.docker.io", -1, null);
HttpClientConfig httpCfg = HttpClientConfig.builder().maxRetries(2).build();
CacheAdapter cache = null; // optional
RegistryClient client = new RegistryClientImpl(endpoint, httpCfg, cache);

var manifest = client.fetchManifest("library/busybox", "latest").manifest();
var layer = manifest.layers().getFirst();
File tmp = Files.createTempFile("layer", ".tar").toFile();
var res = client.fetchBlob(new BlobRequest("library/busybox", layer.digest(), layer.size(), layer.mediaType()), tmp);
```

### Media types
- OCI: manifest/index/config/layers (zstd/gzip/tar).
- Docker schema2: manifest/list/config/layers.
- Accept header includes OCI and Docker.

### Tests
- Unit: manifest/index parsing, WWW-Authenticate, URI/Range, digest/size.
- Integration — `RegistryLocalTest`.
- Live (Docker Hub) — `RegistryLiveTest` (alpine/busybox), runnable locally.
- Stress (tag `stress`, not in CI): many small layer downloads, retries.

### Constraints / architecture
- Client does not choose source (cache/P2P/registry) and is runtime-agnostic; Orchestrator/Runtime Adapter decides.
- Client validates digest/size on download; final verification/import is in the Runtime Adapter layer.

