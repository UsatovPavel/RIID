# Published bench results — 20-image dataset

The final run of each scenario of the containerd and podman matrix (AGENT-130 and the AGENT-119/120
rebench) and the Porto scenarios (AGENT-132), copied verbatim out of `../output/`. That directory stays untracked scratch: every
retry lands there, only the runs below are published.
Dataset: [`dataset_top20_sizes.tsv`](../../config/imagelist/dataset_top20_sizes.tsv).

## Files

| Scenario | Engine | Source | File | Stand |
|-----|--------|--------|------|-------|
| `bare-containerd` | containerd | registry, direct | `bare-containerd.agent130-20260913-0016.tsv` | A |
| `dfinit-containerd` | containerd | Dragonfly via dfinit mirror | `dfinit-containerd.agent130-20260913-0108.tsv` | A |
| `riid-containerd-noprefix` | containerd | RIID + Dragonfly, archive import | `riid-containerd-noprefix.agent130-20260913-0128.tsv` | A |
| `riid-containerd` | containerd | RIID + Dragonfly, prefix import | `riid-containerd.agent130-20260913-0201.tsv` | A |
| `bare-podman` | podman | registry, direct | `bare-podman.agent119120-20260913-1329.tsv` | B |
| `dfinit-podman` | podman | Dragonfly via dfinit mirror | `dfinit-podman.agent119120-20260913-1407.tsv` | B |
| `riid-podman` | podman | RIID + Dragonfly, archive import | `riid-podman.agent119120-20260913-1344.tsv` | B |
| `bare-porto` | Porto | registry, direct (TLS entry) | `bare-porto.agent132-20260913-1627.tsv` | C |
| `riid-porto` | Porto | RIID + Dragonfly, layer-by-layer import | `riid-porto.agent132-20260913-1718.tsv` | C |
| `bare-containerd` | containerd | registry, direct (anchor) | `bare-containerd.agent132-20260913-1700.tsv` | C |

Every run is 10 pods × 20 images, `recreate` mode (all pods start together), RIID v0.4.14.
All ten passed `../summarize/validate-arm.sh`: 20/20 images, zero failed pulls, cold start on
every node. Podman has no prefix-import scenario: over the libpod socket RIID imports whole images.

## Format

Comma-separated despite the `.tsv` name — this is what the run driver writes and what
`../summarize/scenario-metrics.sh` reads. Nine columns:

`scenario,mode,image,pod,backend,start_ms,end_ms,duration_ms,exit_code`

- `pod` is a pod name, except for the **`AGGREGATE`** row emitted once per image: first pod
  start to last pod finish, i.e. the cluster wall-clock for that image. Summing `AGGREGATE`
  over the 20 images gives the number to report. A mean over pods is a second number at best —
  it hides stragglers, and the fleet is only ready when the last pod is.
- `backend` holds the scenario label, the same on every row of a file.
- Trailer lines `# registry_tx_bytes_before/after/delta` carry registry egress in bytes;
  `delta` is the traffic the run caused.

Read one file with `bash ../summarize/scenario-metrics.sh <file>`; compare two with
`bash ../summarize/cluster-aggregate-time.sh <a> <b>`.

## Stands

A stand is one Terraform-created cluster; A, B and C are distinct clusters, identified by their
node sets. Each engine row was measured as one series on one stand, so scenarios compare cleanly
**within a row**. Across rows they do not: the same scenario re-measured on another stand has
drifted by up to 46% (`dfinit-containerd`, 623.1 s vs 426.9 s in earlier runs), more than
most of the effects measured here.

Two limits of these numbers. `bare-containerd` measured twice on stand A gave 878.0 s and
836.0 s (4.8%), which is larger than the prefix-versus-archive gap of 1.9%. Also, egress is
the robust result: `dfinit-podman` egress matched to 0.2% on three separate stands, where its
time did not.

Stand C is the self-managed kubeadm cluster from `terraform-porto` (Ubuntu 22.04, cgroup v1,
Porto 5.3.58, same flavor and disks, 10 workers + 4 infra nodes), since MKS cannot run Porto.
RIID there is `v0.4.14` rebuilt with the Porto 5.3.58 client. Its `bare-containerd` run
is the only link to A: 921.8 s against 836.0 s, so stand C is about 10% slower. Porto 5.3.58
fetches blobs over https only, so `bare-porto` pulled through a TLS listener on the same
registry storage.
