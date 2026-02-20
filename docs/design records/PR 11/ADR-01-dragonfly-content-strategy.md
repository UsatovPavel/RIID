# ADR-01: Dragonfly-First Content Strategy with Controlled Fallback

## Context
The Dragonfly experiment confirmed that Rust `dfget` and `dfcache` provide a stable P2P data path, warm-cache reuse, and persistent cache lifecycle (`import/stat/export`) when Redis is enabled in `scheduler.yaml`.

Current RIID architecture is modular: `dispatcher` orchestrates, `p2p` executes Dragonfly integration, `cache` stores local artifacts/tokens, and `client` handles registry transport. The experiment also proposed a stronger long-term target (`ContentStore`/`FetchStrategy` and a single CAS ownership model), which is larger than a pure adapter change.

We need a decision that allows PR11 to deliver production value now, while still enabling controlled architectural evolution.

## Decision
- Keep module boundaries intact for PR11 (`dispatcher` / `p2p` / `cache` / `client`).
- Use explicit mode-based policy:
  - **Mode A (Dragonfly enabled and healthy):** `Dragonfly-first`, then fallback to native registry path on recoverable Dragonfly failures.
  - **Mode B (Dragonfly disabled or unhealthy):** `cache-first -> registry client`.
- Encapsulate all Dragonfly CLI details (`dfget`, `dfcache`) inside the P2P adapter boundary.
- Standardize the minimal command contract for PR11:
  - `dfget -e <socket> -O <out> <url>` with approved flags only (`--digest`, `--tag`, `--application`, `--timeout`, `-H`).
  - `dfcache import/stat/export` by `task_id`.
  - Treat `AlreadyExists` on `import` as successful reuse.
- Define Redis in `scheduler.yaml` as a required precondition for persistent cache flows.
- Defer full `ContentStore`/`FetchStrategy` unification and single-CAS ownership redesign to a later architectural phase, after PR11 stabilization data is collected.

## Consequences
- PR11 scope stays implementable without cross-module rewrite.
- Reliability improves immediately through health-check driven fallback policy.
- Operational flow becomes explicit and testable via scenario matrix (cold, warm, fallback, `dfcache import/stat/export`).
- The design keeps a clean migration path for future rules-level architecture changes, using measured PR11 results instead of speculative refactoring.
