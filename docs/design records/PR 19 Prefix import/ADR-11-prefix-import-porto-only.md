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

- **Measured on the Porto VM.** `library/python:latest` (395 MiB, 7 layers, 4 A/B
  pairs): wall 55.0s → 49.2s median (**−10.5%**), prefix faster in every pair.
  Widened to python plus the 9 images closest to it in size (one A/B pair each):
  **faster on 10 of 10**, median **−10.9%**, and the work left after the layout is
  assembled drops from 9.6s to 0.28s median. Details, the per-image table and the
  decomposition of the win are in `zOptimization/Plan/PlanPrefixImport.md` §7.
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
  change in the app layer. Podman has since been **measured and implemented** on
  exactly that contract: it has no per-layer command, so a prefix is handed over
  as a whole small image built from the layers that arrived, and
  `containers/storage` keys a layer by chain-id (`storage_dest.go:1043` -
  `layerID(parentLayer, trusted)`) so nothing is extracted twice. Measured on
  podman 5.7.0 against the registry: faster on 10 of 10 images, median **-7.2%**
  wall. Less than Porto's -10.9%, because every prefix re-runs the whole
  `copy.Image()` and extracts one layer at a time instead of podman's six
  parallel copies. It is **off by default** (`runtime.podmanPrefixImportStride:
  0`) - the extra imports only pay for themselves when a download is there to
  hide them. Numbers, the stride-2 arm and the caveats are in
  `zOptimization/Plan/PlanPrefixImportPodman.md`.
- **containerd followed, on the same contract.** It has no per-layer import
  either, but it keeps a content store, and its importer ingests only what the
  tar holds without checking that every referenced blob is present
  (`core/images/archive/importer.go`, `ImportIndex`). So a prefix tar carries
  only the layers added since the last one and the rest resolves from the store,
  keeping bytes streamed linear in image size rather than quadratic; reuse then
  happens on snapshots, which are keyed by chain-id. Measured on containerd
  2.2.1: faster on 10 of 10 images, median **-8.3%** wall.
- **Stride 1 is the production default** (`RuntimeAdapter.DEFAULT_PREFIX_IMPORT_STRIDE`,
  overridable as `runtime.prefixImportStride`; 0 turns prefix import off). A
  stride of 2 was measured on both engines and was worse on both (-6.3% podman,
  -6.6% containerd) and the only arm that produced regressions: what it saves in
  engine invocations it loses by starting the import later.
