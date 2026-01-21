## Cache module

### Purpose
Cache downloaded OCI artifacts (layers/configs) and auth tokens to avoid repeat registry fetches.

### Responsibilities
- Store blobs by digest/media type and return a reusable file path instead of re-downloading.
- Support temporary and durable filesystem backends; clean up temp storage when closed.
- Preserve size/media-type metadata and validate digests; surface locator keys for reuse.
- Cache auth tokens with per-entry TTL to reduce registry auth round-trips.

### Core types
- `CacheAdapter`, `CacheEntry`, `CacheMediaType`, `CachePayload`/`PathCachePayload`, `ImageDigest`.
- `FileCacheAdapter` (persistent dir) and `TempFileCacheAdapter` (ephemeral; implements `AutoCloseable`).
- `ValidationException` for digest/metadata errors.
- `TokenCache` (Caffeine-backed, per-entry TTL) for registry auth tokens.

### Wiring
- Default wiring in `ImageLoadServiceFactory` uses `TempFileCacheAdapter`.
- Registry client and P2P layer write into cache; dispatcher/runtime read back via resolved paths.
- Token cache is used by auth flows inside the registry client.

### Config
- No dedicated YAML section yet; backends are chosen by wiring (factory/tests).

### Tests
- Unit: `TempFileCacheAdapterTest` (roundtrip, size inference, missing entry, idempotent cleanup).
- Integration (multi-module): `integration/client_cache/RegistryClientImplTest`, `integration/client_cache/StressTest` (client+cache), `integration/dispatcher_cache/SimpleRequestDispatcherTest` (dispatcher+cache paths).

