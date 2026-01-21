# Tests layout and how to run

## Layers
- `src/test/java/<module>/...`: unit tests scoped to a single module/package (keep package-private helpers close to code).
- `src/test/java/riid/integration/<scope>/...`: cross-module integration (e.g., `runtime_app`, `client_cache`, `config_client`, `dispatcher_*`). Use this for multimodule flows and live registry/runtime checks.
- `src/test/java/riid/e2e/...`: end-to-end CLI-to-runtime scenarios (minimal, black-box style).

## Tags / long-running
- Live/registry/podman tests may need network or local runtimes; skip them when offline (e.g., `--exclude-task integrationTest` or selective `--tests`).
- E2E/CLI tests often shell out; prefer running them explicitly when changing CLI/runtime glue.

## Commands
- Fast cycle (unit only): `./gradlew test --tests "*unit*"`
- Integration focus: `./gradlew test --tests "riid.integration.*"`
- Full suite (if env ready: network, podman/porto): `./gradlew test`

## Conventions
- Name integration folders by the main modules touched (`runtime_app`, `client_cache`, `config_client`, `dispatcher_runtime`, etc.) for discoverability.
- Keep unit tests within their module