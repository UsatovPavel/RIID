## Config module

### Purpose
Parse and validate the application YAML config (registry client + dispatcher). Loader: `ConfigLoader`, rules: `ConfigValidator`.

### Modules layout
- Each module owns its config class in its module package (e.g., client/dispatcher/runtime/app).
- `GlobalConfig` is the aggregator (config module) that stitches module configs together.

### Minimal valid example
```yaml
client:
  http: {}
  auth:
    defaultTokenTtlSeconds: 300
  registries:
    - scheme: https
      host: registry.example.com
      port: -1
dispatcher:
  maxConcurrentRegistry: 1
```

### Optional app section
```yaml
app:
  tempDirectory: "/var/tmp/riid"
  allowedRegistries:
    - registry.example.com
```

### Optional `app.daemon` (long-lived daemon / IPC)
Used when the process is started with `--daemon` (see [app.md](app.md)). All fields are optional; unset values use defaults below.

```yaml
app:
  daemon:
    unixSocketPath: "/run/riid/riid.sock"   # HTTP control plane: POST /pull over Unix domain socket
    metricsHost: "0.0.0.0"                 # TCP: GET /metrics (Prometheus-style placeholder)
    metricsPort: 9090
    maxConcurrentPulls: 32                  # global semaphore before each pull
    requestTimeout: PT30M                   # per-request ceiling for loader work
    overloadPolicy: REJECT                  # only value supported today
```

- Omit `app.daemon` entirely — defaults below apply.
- Provide `app.daemon` with only some keys — missing keys use the same defaults.
- `unixSocketPath` (when set) must be **&lt; 108 bytes** (AF_UNIX path limit).
- `overloadPolicy` must be `REJECT` if specified.

### Optional runtime settings
```yaml
runtime:
  dockerCmd: "/usr/bin/docker" 
  maxTasksCommandExecutor: 16  
  output:
    maxStdoutBytes: 32768
    maxStderrBytes: 32768
    captureStdout: true
    captureStderr: false
```

### Optional P2P Dragonfly section
```yaml
p2p:
  dragonfly:
    enabled: true
    dfdaemonAddr: "unix:///var/run/dragonfly/dfdaemon.sock"
    schedulerAddr: "dfscheduler:65002"
    requestTimeout: PT30S
    maxRetries: 2
```

### Defaults (from smoke test)
- client.http.connectTimeout = PT5S
- client.http.requestTimeout = PT30S
- client.http.maxRetries = 2
- client.http.initialBackoff = PT0.2S
- client.http.maxBackoff = PT2S
- client.http.backoffExponentBase = 2
- client.http.retryIdempotentOnly = true
- client.http.userAgent = riid-registry-client
- client.http.followRedirects = true (для GHCR обязательно оставить включённым)
- client.partialDownloading.mode = AUTO
- client.partialDownloading.partialDigestValidation = SKIP
- client.partialDownloading.retryWithoutRangeOnUnsatisfiableRange = true
- client.http.maxRedirects = 5
- client.auth.defaultTokenTtlSeconds = 300
- client.auth.certPath / keyPath / caPath = null
- client.registries.size = 1
- `app.daemon` runtime defaults when the block or a field is omitted:  
  `unixSocketPath` → `/tmp/riid.sock`, `metricsHost` → `0.0.0.0`, `metricsPort` → `9090`,  
  `maxConcurrentPulls` → `32`, `requestTimeout` → `PT30M`, `overloadPolicy` → `REJECT`

### Validation rules (ConfigValidator)
- `client`, `dispatcher`, `registries` required; at least one registry with `scheme` and `host`.
- `dispatcher.maxConcurrentRegistry` > 0.
- `client.http`: timeouts/backoff > 0, `initialBackoff <= maxBackoff`, `backoffExponentBase >= 2`, `userAgent` not blank, `maxRetries` and `maxRedirects` must be >= 0.
- `client.auth.defaultTokenTtlSeconds` > 0.
- `client.auth.certPath` and `client.auth.keyPath` must be set together (mTLS pair policy).
- `client.auth.certPath`/`keyPath`/`caPath`, if provided, must:
  - exist,
  - point to regular files,
  - be readable.
- `app.tempDirectory`, if present, must not be blank; `app.allowedRegistries` entries must not be blank.
- `app.daemon`: if present, `unixSocketPath` (if set) non-blank and &lt; 108 bytes; `metricsHost` (if set) non-blank; `metricsPort` (if set) in 1..65535; `maxConcurrentPulls` (if set) &gt; 0; `requestTimeout` (if set) positive; `overloadPolicy` (if set) only `REJECT`.
- `runtime.dockerCmd`, if present, must not be blank.
- `runtime.maxTasksCommandExecutor`, if present, must be > 0.
- `runtime.output.maxStdoutBytes`/`runtime.output.maxStderrBytes` must be > 0 when capture is enabled.
- `p2p.dragonfly.dfdaemonAddr` must not be blank when enabled; `schedulerAddr` must not be blank when set; `maxRetries` must be >= 0; `requestTimeout` must be positive when set.

### Known notes
- Missing `registries` throws `ConfigValidationException`.
- Для GHCR скачивание blob/manifest использует 302/307 CDN, поэтому `client.http.followRedirects` должен быть true (явно прописывать в config/config.yaml).
- Partial downloading: `partialDigestValidation=SKIP` means digest is validated only for a full blob; `retryWithoutRangeOnUnsatisfiableRange` enables retry without Range.
- Runtime TLS/Auth errors use unified safe message prefixes:
  - `SECURITY:TLS:<kind>: ...`
  - `SECURITY:AUTH:<kind>: ...`

### Tests
- `ConfigBranchTest`: validation branches (including maxRetries < 0, missing http/auth/registries/dispatcher).
- `ConfigLoaderTest` (integration/config_client): loading/validation from files.
- Smoke: `smokePrintsDefaultsFromMinimalConfig` — prints actual defaults after loading minimal valid YAML.

