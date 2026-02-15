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

### Optional runtime settings
```yaml
runtime:
  dockerCmd: "/usr/bin/docker" # optional, default: "docker"
  output:
    maxStdoutBytes: 32768
    maxStderrBytes: 32768
    captureStdout: true
    captureStderr: false
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
- client.range.mode = AUTO
- client.range.partialDigestValidation = SKIP
- client.range.fallbackOn416 = true
- client.http.maxRedirects = 5
- client.auth.defaultTokenTtlSeconds = 300
- client.auth.certPath / keyPath / caPath = null
- client.registries.size = 1

### Validation rules (ConfigValidator)
- `client`, `dispatcher`, `registries` required; at least one registry with `scheme` and `host`.
- `dispatcher.maxConcurrentRegistry` > 0.
- `client.http`: timeouts/backoff > 0, `initialBackoff <= maxBackoff`, `backoffExponentBase >= 2`, `userAgent` not blank, `maxRetries` and `maxRedirects` must be >= 0.
- `client.auth.defaultTokenTtlSeconds` > 0; cert/key/ca paths, if provided, must exist.
- `app.tempDirectory`, if present, must not be blank; `app.allowedRegistries` entries must not be blank.
- `runtime.dockerCmd`, if present, must not be blank.
- `runtime.output.maxStdoutBytes`/`runtime.output.maxStderrBytes` must be > 0 when capture is enabled.

### Known notes
- Missing `registries` throws `ConfigValidationException`.
- Для GHCR скачивание blob/manifest использует 302/307 CDN, поэтому `client.http.followRedirects` должен быть true (явно прописывать в config/config.yaml).
- Range: `partialDigestValidation=SKIP` means digest is validated only for a full blob; `fallbackOn416` enables retry without Range.

### Tests
- `ConfigBranchTest`: validation branches (including maxRetries < 0, missing http/auth/registries/dispatcher).
- `ConfigLoaderTest` (integration/config_client): loading/validation from files.
- Smoke: `smokePrintsDefaultsFromMinimalConfig` — prints actual defaults after loading minimal valid YAML.

