# ADR-005: Make ImageLoadFacade AutoCloseable

## Context
`ImageLoadFacade` builds internal resources when created from config (registry client, temp cache). Without an explicit lifecycle, these resources can leak across CLI runs and tests.

## Decision
- `ImageLoadFacade` implements `AutoCloseable`.
- `createFromConfig` passes an owned cache closer; `close()` shuts down the registry client and owned cache.
- `createDefault` does not register a cache closer because the cache is provided by the caller (no ownership).

## Consequences
- CLI and tests can use try-with-resources to clean up temp cache and client resources.
- Ownership is explicit: only resources created inside the facade are closed.
- Callers passing shared caches must manage their own lifecycle.

