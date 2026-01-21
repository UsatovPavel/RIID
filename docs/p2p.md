## P2P Stub

Purpose: placeholder executor for peer-to-peer distribution; current implementation is a stub used by dispatcher/tests.

- Core type: `riid.p2p.P2PExecutor` with default no-op/`NoOp` implementation.
- Responsibilities today: satisfy interfaces; no network side effects.
- Future work: implement actual peer discovery, fetch/push, error handling, metrics.
- Config: none yet; wiring occurs via `ImageLoadServiceFactory.defaultRuntimes()`/dispatcher setup.
- Tests: dispatcher integration tests cover interactions; no dedicated P2P behavior until real implementation lands.

