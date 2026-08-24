# ADR-11: Prefix import is an opt-in adapter capability, implemented only for Porto

## Context

Until now every import path in RIID had the same shape: download **all** blobs of an
image in parallel, then hand the finished artifact to the runtime. The barrier is
`OciArchiveBuilder.buildOciDirectory()` — `invokeAll(...)` plus a loop over the
futures. Nothing is imported until the slowest blob lands.

Measurements across the epic put `engine.import` at 76% of a cold request in the
cluster trace (`zOptimization/Optimization.md`) and at ~13s of a ~56s request on the
Porto VM. AGENT-73 already established that the import itself cannot be made faster
from outside the engine (podman's `load` and `pull oci:` converge on the same
`copy.Image()`), so the remaining lever is **when** it runs, not how fast it is.

Two constraints shaped the design:

1. Not every runtime can take a layer at a time. `portoctl layer -I` imports exactly
   one layer from one local file, and the chain is assembled later at `vcreate`.
   `podman load`, `ctr images import` and `docker load` are atomic over a whole image.
2. Even Porto cannot always do it: an image whose layer chain does not fit a Porto
   private value (4096 bytes, ~50 layers) has to take the flattened rootfs path, which
   is one tar by construction.

## Decision

- Add an **optional** adapter capability rather than changing the `RuntimeAdapter`
  contract for everyone:
  `supportsIncrementalImport(Manifest)` + `beginIncrementalImport(imageName, manifest)`
  → `IncrementalImageImport` (`importLayer` … `finish`, `AutoCloseable`).
  Defaults are `false` / `UnsupportedOperationException`, so Podman, Docker and
  containerd keep their exact current path.
- The capability is answered **per manifest**, not per adapter, so Porto can decline
  an image whose chain will not fit its metadata before anything is downloaded,
  instead of discovering it mid-import.
- The consumer is the **calling thread** in `OciArchiveBuilder.streamLayers(...)`,
  walking the manifest in order while pulls keep running on virtual threads. Layers
  are imported strictly in manifest order (the "prefix"), one at a time — not
  N concurrent `portoctl` processes competing with the downloads for CPU and disk.
- The image is published only in `finish()` (Porto: the marker layer carrying the
  chain), after the last layer.

## Consequences

- **Measured on the Porto VM** (`library/python:latest`, 395 MiB, 7 layers, 4 A/B
  pairs): wall time 55.0s → 49.2s median (**−10.5%**); the work left after the last
  byte is downloaded collapses from ~12.8s to ~4.9s. Details and the decomposition
  of that win are in `zOptimization/Plan/PlanPrefixImport.md` §7.
- Part of the win is not overlap: the incremental path also skips building a ~400 MiB
  `oci-archive` tar and re-extracting it inside the adapter. Both effects follow from
  the same change and are reported separately rather than attributed to overlap alone.
- A failed pull now fails a consumer that is already importing. Aborting mid-way
  leaves `riid-layer-<digest>` entries in Porto — that is deliberate: they are
  content-addressed and the next pull reuses them; no image is published, so half an
  image never resolves as a finished one.
- The Porto marker layer is now named after the image reference (sanitized to
  `PORTO_NAME_CHARS`) instead of a temp archive filename. A name derived from a
  reference can collide with an earlier import of the same tag, so the incremental
  path removes the old marker first; otherwise a re-pushed tag would keep resolving to
  the chain imported under that name first.
- Adding the capability to another runtime is a per-adapter decision and needs no
  change in the app layer. Podman is **deferred, not ruled out**: calling `podman load`
  once per growing prefix would work, because `containers/storage` keys a layer by
  chain-id (`storage_dest.go:1043` — `layerID(parentLayer, trusted)`) and reuses a
  layer already in the store instead of re-extracting it. That trades the overlap
  against N runs of `copy.Image()` with its per-blob reuse checks — the same checks
  that produced AGENT-73's regression — so it needs its own measurement on the podman
  stand (`bench/`), not this VM. Open questions and the distinction between *overlap*
  and *chain-id reuse* are written up in
  `zOptimization/Research/ResearchPipelineOverlapPrecedent.md` §6.
