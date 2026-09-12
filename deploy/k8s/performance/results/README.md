# Published bench results — 20-image dataset

The final run of each arm of the AGENT-117/118/119/120 matrix, copied verbatim out of
`../output/` (which stays untracked scratch: every retry lands there, only the runs below
are published). Dataset: [`dataset_top20_sizes.tsv`](../../config/imagelist/dataset_top20_sizes.tsv).

## Files

| Arm | Engine | Source | File | Stand |
|-----|--------|--------|------|-------|
| `bare-containerd` | containerd | registry, direct | `bare-containerd.agent117-20260910-2248.tsv` | A |
| `dfinit-containerd` | containerd | Dragonfly via dfinit mirror | `dfinit-containerd.agent117-20260910-2228.tsv` | A |
| `riid-containerd-noprefix` | containerd | RIID + Dragonfly, archive import | `riid-containerd-noprefix.agent118-20260911-1956.tsv` | **B** |
| `bare-podman` | podman | registry, direct | `bare-podman.agent119-20260912-1647.tsv` | C |
| `dfinit-podman` | podman | Dragonfly via dfinit mirror | `dfinit-podman.agent119-20260912-2004.tsv` | C |
| `riid-podman` | podman | RIID + Dragonfly | `riid-podman.agent120-20260912-1620.tsv` | C |

Every run is 10 pods × 20 images, `recreate` mode (all pods start together).

## Format

Comma-separated despite the `.tsv` name — this is what the run driver writes and what
`../summarize/scenario-metrics.sh` reads. Nine columns:

`scenario,mode,image,pod,backend,start_ms,end_ms,duration_ms,exit_code`

- `pod` is a pod name, except for the **`AGGREGATE`** row emitted once per image: first pod
  start to last pod finish, i.e. the cluster wall-clock for that image. Summing `AGGREGATE`
  over the 20 images gives the number to report. A median or mean over pods is a second
  number at best — it hides stragglers, and the flight is only ready when the last pod is.
- `backend` holds the arm label, the same on every row of a file.
- Trailer lines `# registry_tx_bytes_before/after/delta` carry registry egress in bytes;
  `delta` is the traffic the run caused.

Read one file with `bash ../summarize/scenario-metrics.sh <file>`; compare two with
`bash ../summarize/cluster-aggregate-time.sh <a> <b>`.

## Why the stand column matters

A stand is one Terraform-created cluster; the letters above are distinct clusters, identified
by their node sets. The same arm re-measured on a different stand has drifted by up to 46%
(`dfinit-containerd`: 623.1 s vs 426.9 s), which is larger than most effects being measured.
So arms are only comparable **within one stand letter**.

That makes the podman row a like-for-like triple (all C) and the containerd baselines a valid
pair (both A), but `riid-containerd-noprefix` sits alone on B: it has no same-stand baseline
to be compared against, and it is the no-prefix variant, not the prefix import arm. Closing
that cell needs `bare-containerd`, `dfinit-containerd` and `riid-containerd` re-run as one
series on one stand — until then the matrix in the top-level READMEs is left unfilled.
