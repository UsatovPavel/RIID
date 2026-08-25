# ADR-12: Engine-side import switches are exposed, not turned on

## Context

AGENT-95 asked for four engine-configuration levers on the second half of
`engine.import` — the part that is not gunzip: podman `pull_options.use_hard_links`,
and containerd's `--discard-unpacked-layers`, `--snapshotter`, `--no-unpack`. The
ticket framed them as "no code needed, engine configuration only". Measurement
disagreed on three of the four, so each is recorded here with the number that
decided it. Full arms: `zOptimization/Plan/PlanEngineSideOptimizations.md` §6.

## Decision

- **podman `use_hard_links`: not adopted, no code.** It is read by the chunked
  differ, which runs only for zstd:chunked images pulled from a registry. RIID hands
  podman a local OCI layout (`podman load` / `podman pull oci:`), so the knob cannot
  fire: 3 A/B rounds left the store byte-identical. It produces -11.3% store only
  together with `convert_images`+`enable_partial_images` (+12% time), which is item
  1.5 and out of this ticket's scope.
- **containerd `--discard-unpacked-layers`: implemented, off by default.** -27.3%
  total store (content store 390 MiB → 76 KiB) at no cost in time. Off by default
  because it makes `ctr images export` and push fail for that image — acceptable for
  RIID, whose cache still holds the blobs, but not a decision to make for the user
  silently.
- **containerd `--snapshotter`: implemented, default unchanged.** The field existed
  but was unreachable — `ImageLoadingFacade` always passed `null` — so exposing it
  *was* the work. The default `overlayfs` stays: `native` costs +85% time and 4x the
  disk, and no third snapshotter is usable on the stand.
- **containerd `--no-unpack` on prefixes: implemented, off by default.** Total time
  is unchanged, but the non-overlapped tail grows 0.7 s → 11.6 s, because the unpack
  moves out of the window that overlaps the download. This answers the question the
  ticket raised about prefix import: the intermediate unpacks are not overhead, they
  are the same work done where it is free (see ADR-11).
- The two flags `ctr` refuses together are chosen by import step inside
  `importCommand(boolean prefixStep)`, so no caller can assemble an illegal command.
  `--discard-unpacked-layers` also silently requires `--local`; the adapter adds it.

## Consequences

- Default behaviour is byte-for-byte the previous command line. Three optional
  `runtime.containerd*` keys, validated in `ConfigValidator`, documented in
  `docs/config.md` with the number behind each default.
- `--discard-unpacked-layers` is safe under prefix import — verified end to end on a
  clean containerd, image still runs — because the next prefix builds on the
  snapshot chain, not on the blobs the GC removes.
- Three of the four items are negative results. They are kept in the plan with their
  measurements so the same knobs are not retried on intuition.
