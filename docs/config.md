## Config module

### Purpose
Parse and validate the application YAML config (registry client + dispatcher). Loader: `ConfigLoader`, rules: `ConfigValidator`.

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

### Defaults (from smoke test)
- client.http.connectTimeout = PT5S
- client.http.requestTimeout = PT30S
- client.http.maxRetries = 0
- client.http.initialBackoff = PT0.2S
- client.http.maxBackoff = PT2S
- client.http.retryIdempotentOnly = false
- client.http.userAgent = riid-registry-client
- client.http.followRedirects = true (для GHCR обязательно оставить включённым)
- client.auth.defaultTokenTtlSeconds = 300
- client.auth.certPath / keyPath / caPath = null
- client.registries.size = 1

### Validation rules (ConfigValidator)
- `client`, `dispatcher`, `registries` required; at least one registry with `scheme` and `host`.
- `dispatcher.maxConcurrentRegistry` > 0.
- `client.http`: timeouts/backoff > 0, `initialBackoff <= maxBackoff`, `userAgent` not blank, `maxRetries` must be >= 0.
- `client.auth.defaultTokenTtlSeconds` > 0; cert/key/ca paths, if provided, must exist.

### Known notes
- Missing `registries` throws `ConfigValidationException`.
- Для GHCR скачивание blob/manifest использует 302/307 CDN, поэтому `client.http.followRedirects` должен быть true (явно прописывать в config.yaml).

### Tests
- `ConfigBranchTest`: validation branches (including maxRetries < 0, missing http/auth/registries/dispatcher).
- `ConfigLoaderTest` (integration/config_client): loading/validation from files.
- Smoke: `smokePrintsDefaultsFromMinimalConfig` — prints actual defaults after loading minimal valid YAML.

