# AGENT-97 — plain OCI zstd benchmark

This benchmark answers two separate questions for the same logical image:

1. Does plain OCI zstd work through the real RIID daemon into real Podman?
2. How does it compare with gzip in direct Podman and RIID → Podman paths?

It is intentionally separate from the AGENT-72 Dragonfly-warm handoff benchmark.
P2P is disabled here so a codec result cannot be invalidated by different
Dragonfly cache state.

## Arms

| Arm | Real execution path | Primary metric |
|---|---|---|
| `podman-gzip` | registry → Podman | wall time |
| `podman-zstd` | registry → Podman | wall time |
| `riid-podman-gzip` | registry → RIID → OCI layout/prefixes → Podman | wall and RIID stages |
| `riid-podman-zstd` | registry → RIID → OCI layout/prefixes → Podman | wall and RIID stages |

Every sample gets a fresh isolated Podman `overlay` graphroot. A temporary
wrapper passes explicit global `podman --root`, `--runroot` and
`--storage-driver overlay` options; this also works on Podman versions that
ignore `CONTAINERS_STORAGE_CONF`. Cleanup enters Podman's user namespace and
removes only that sample's graphroot. It deliberately avoids `podman system
reset`: rootless Podman has shared runtime state outside the explicit store.
Arm order rotates between rounds, and warmups are excluded from medians.

## Artifact correctness gate

The two variants are published as separate tags, then pinned by separate
manifest digests. `prepare` refuses the pair unless all of the following hold:

- both manifests are OCI image manifests with identical layer count;
- every gzip layer is `application/vnd.oci.image.layer.v1.tar+gzip`;
- every zstd layer is `application/vnd.oci.image.layer.v1.tar+zstd`;
- config digest and ordered `rootfs.diff_ids` are identical.

This proves that recompression changed transport bytes, not filesystem content
or runtime configuration. The metadata also records both compression levels,
commands, conversion time, compressed sizes and the Podman version.

## Local run

Start a disposable registry. Use a unique name and remove only that container
after the run:

```bash
podman run -d --name riid-agent97-registry -p 127.0.0.1:5000:5000 docker.io/library/registry:2
```

Choose a local multi-layer source image. The default below is the small image
already used by RIID's live Podman tests:

```bash
make -C bench zstd-prepare SOURCE=localhost/library/jobber:latest
./gradlew shadowJar
make -C bench zstd-check
make -C bench zstd-bench N=10
```

For an execution-level correctness check, invoke the script directly and pass a
command valid for the selected image; it runs after each timed pull and is not
included in `wall_ms`:

```bash
python3 bench/zstd_bench.py run --iterations 10 --smoke-command 'python --version'
```

Generated artifact metadata, TSV measurements and per-sample RIID daemon logs
are written below `bench/results/` (git-ignored). A digest is sent to both
Podman and RIID, so tag movement during a run cannot change the artifact.

Cleanup:

```bash
podman rm -f riid-agent97-registry
```

For a remote registry, pass `REGISTRY=https://host[:port]` and a repository for
which the current Podman login has push permission. The current harness assumes
an anonymous registry for RIID reads; authenticated RIID credentials are not
written into benchmark files.

## Interpretation

The direct pair measures registry transfer plus Podman's ingestion. The RIID
pair measures registry transfer, RIID layout construction and Podman's actual
import; `t_dl_end`, layout, import and handoff timings come from RIID's
structured log for the sample's unique trace ID. Report results only when every
measured sample exits successfully and the artifact gate passes.

This ticket covers plain zstd on the Podman path. `zstd:chunked`, Podman's
partial-pull/file-reuse path, Dragonfly behavior and non-Podman runtimes are
different experiments.

## Validated result

The final cold-store run used `AI_Box` (6 vCPU, 16.5 GiB RAM, Linux
6.8.0-138, Java 23.0.2, rootless Podman 4.9.3 with overlay). The logical source
was `localhost/riid/python:latest`; the artifact gate proved one config digest,
the same seven ordered `diff_ids`, and separate immutable manifests. Plain zstd
used 362,806,363 compressed layer bytes versus 425,050,045 for gzip (−14.6%).

One warmup was excluded. All 20 measured samples succeeded, all real RIID
samples fetched `registry:8`, and every imported image ran `Python 3.14.4` after
the timed interval.

| Path | gzip median, N=5 | zstd median, N=5 | Difference |
|---|---:|---:|---:|
| direct Podman | 16,727 ms | 10,112 ms | **−39.5%** |
| real RIID → Podman | 22,241 ms | 15,495 ms | **−30.3%** |

For RIID, median download-end changed from 1,884 to 1,347 ms and handoff from
19,911 to 13,991 ms. With prefix import, the `archive.build − t_dl_end` interval
includes the real intermediate Podman imports; it changed from 19,485 to
13,441 ms. Final import was already a reuse step and stayed small (426/483 ms).

The first attempted run was discarded after a graphroot check proved Podman
4.9.3 ignored `CONTAINERS_STORAGE_CONF`. The final harness uses explicit global
`--root`/`--runroot` options and fails before timing unless the actual graphroot
matches and contains zero images.
