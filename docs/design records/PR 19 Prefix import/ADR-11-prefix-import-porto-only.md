# ADR-11: Prefix import as an optional adapter capability

## Context

Every import used to wait for the whole image, and `engine.import` was 76% of a cold
request. AGENT-73 showed the import cannot be made faster from outside the engine, so
the remaining lever is **when** it runs. Engines differ in what they accept:
`portoctl layer -I` takes one layer, `podman load` and `ctr images import` do not.

## Decision

- An **optional** capability, not a change to `RuntimeAdapter` for everyone:
  `supportsIncrementalImport(manifest)` + `beginIncrementalImport(...)` →
  `IncrementalImageImport`. Answered per manifest, so an adapter can decline an
  image before anything is downloaded.
- The consumer is the calling thread in `OciArchiveBuilder.streamLayers(...)`,
  walking the manifest in order while pulls run on virtual threads.
- Porto imports layers directly. Podman and containerd have no per-layer command, so
  each prefix is handed over as a whole small image built from the layers that
  arrived; the engines reuse what they already unpacked (chain-id in both cases).
- The image is published only in `finish()`, after the last layer.
- On by default (`runtime.prefixImport`), one layer at a time.

## Consequences

- Porto: **-10.9%** wall, faster on 10 images of 10. Podman: **-7.2%**, 10 of 10.
  containerd: **-8.3%**, 10 of 10. Handing over two layers at a time was measured on
  both engines and was worse on both, so the knob is a boolean.
- Part of the win is not overlap: the path also skips building and re-extracting the
  intermediate tar. Reported separately in the plan.
- An aborted pull leaves content-addressed layers but no image.
- Numbers and caveats: `zOptimization/Plan/PlanPrefixImport{,Podman}.md`.
