# ADR-09: Separate Registry and Cluster Providers in Deploy

## Context
`deploy/k8s` mixed two independent concerns:
- cluster-specific behavior (infrastructure/bootstrap orchestration);
- registry-specific behavior (image source mapping, auth, pull endpoints).

This coupling made provider switching partial and fragile: changing registry profile in one module did not guarantee consistent state for `riid`, `dragonfly`, and `performance`.

Refactor introduces a target model where cluster and registry are independent axes:
- `CLUSTER_PROVIDER` selects cluster-side implementation details;
- `REGISTRY_PROVIDER` selects image/auth/runtime registry behavior.

Global config already contains shared deployment state (`CLUSTER_TOPOLOGY.workers`) and infra version pins (`config/infra_versions.yaml`), which should be consumed centrally.

## Decision
- Split provider model into two catalogs:
  - `providers/cluster` for cluster-specific behavior;
  - `providers/registry` for registry/image/auth behavior.
- Treat the deploy orchestrator as cluster-agnostic entrypoint (`bootstrap`), not vendor-specific (`Selectel`).
- Resolve registry data once in orchestrator and pass the resolved block to all modules (`riid`, `dragonfly`, `performance`) instead of letting each module infer provider logic.
- Source cluster state and image versions only from global config:
  - `config/config.yaml` (`CLUSTER_TOPOLOGY`, including workers count);
  - `config/infra_versions.yaml` (pinned infra image versions).
- Keep `TEST_REGISTRY_PROVIDER` only as explicit `performance` test override with validation, not as a separate provider resolution path.

## Consequences
- Cluster migration and registry migration become independent operations.
- Provider switching becomes deterministic: one resolved configuration is applied to all deploy modules.
- Reduced duplication of shell rendering logic and fewer hidden provider-specific branches.
- Better auditability: topology and image versions come from centralized config, not scattered `latest`/hardcoded values.
- Slightly higher upfront structure cost (two provider catalogs + orchestrator contract), but lower long-term maintenance risk.
