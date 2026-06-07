# ADR-10: Move Dragonfly output off tmpfs for large images

## Context
Performance tests in PR18 (`DATASET=B`, image `riid/cimg/android`) fail with `No space left on device (os error 28)` during Dragonfly download/copy phases.
Increase in config tmpfs from 800 Mb to 2Gi - not works, var/run/dragonfly max 800 Mb

Observed behavior:
- RIID/dfdaemon writes payloads under `/var/run/dragonfly/output/p2p-*.bin`;
- multiple large blobs (700-800 MiB each) are downloaded concurrently;
- failures happen in `copy task` and then trigger fallback/failures.

Root cause in deployment values:
- `client.extraVolumes.dragonfly-run-tmpfs` mounts `/run/dragonfly` as `emptyDir.medium: Memory`;
- output was configured via `hostPath` under `/var/run/dragonfly/output`;
- on Linux nodes, `/var/run` resolves to `/run` (tmpfs), so output effectively stayed on memory-backed storage.

As a result, large-image pulls are constrained by tmpfs capacity instead of node disk capacity.

## Decision
- Keep container `mountPath` unchanged for compatibility: `/var/run/dragonfly/output`.
- Change the backing host path to disk-backed storage:
  - from `/var/run/dragonfly/output`
  - to `/var/lib/dragonfly/output` (or another persistent disk path, e.g. `/data/dragonfly/output`).
- Keep tmpfs mount for runtime socket/ephemeral control files only (`/run/dragonfly`).

Operational guardrails for PR18 performance runs:
- ensure host directory exists on worker nodes before rollout;
- re-install/upgrade Dragonfly after values change;
- verify inside `dragonfly-client` pod that `/var/run/dragonfly/output` is not tmpfs.

## Consequences
- Large image pulls are no longer bounded by tmpfs size and stop failing on `ENOSPC` in `copy task`.
- Existing RIID/dfdaemon path contracts remain intact because `mountPath` does not change.
- Node disk capacity and I/O become the governing resource for large blob handling.
- Requires node-level directory management (`/var/lib/dragonfly/output`) and disk capacity monitoring.
