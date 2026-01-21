## Dispatcher

Purpose: orchestrate concurrent pulls across registries/runtime adapters. Main entrypoints live in `riid.dispatcher.*`.

- Core types: `Dispatcher`, `DispatcherConfig`, `SimpleRequestDispatcher` (integration with cache/runtime), `DispatcherRuntimeIntegrator`.
- Responsibilities: queue image pull requests, limit parallelism (`maxConcurrentRegistry`), route to registry client + runtime adapter, surface results to CLI/runtime.
- Config: see `dispatcher` section in config YAML; `maxConcurrentRegistry` must be > 0 (validated by `ConfigValidator`).
- Lifecycle: created via `ImageLoadServiceFactory` (CLI path) or tests; runtime adapters registered per runtime id.
- Tests: integration under `src/test/java/riid/integration/dispatcher_*` (cache/runtime); end-to-end coverage via CLI tests that reach the dispatcher.

