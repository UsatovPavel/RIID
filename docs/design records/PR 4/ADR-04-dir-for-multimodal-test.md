# ADR-04: Directory layout for multimodal (integration/e2e) tests

## Context
Multimodule integration/e2e tests were scattered. Need predictable layout to see which modules/runtimes are involved (client, dispatcher, runtime adapters, cache, CLI).

## Decision
- Place multimodal tests under an internal integration/e2e root with subdirectories named by primary scope (e.g., `integration/runtime_app`, `integration/client_cache`, `integration/config_client`).
- Name folders by key modules touched; do not mix unit-only tests there.
- Keep module-local unit tests near code; cross-module flows go to integration/e2e dirs.

## Consequences
- Easier discovery of cross-cutting tests and their dependencies.
- Clearer separation of pure unit tests vs. integration/e2e vs. live tests.
- Future additions must pick the correct scope folder; reduces ambiguity when skipping live tests.