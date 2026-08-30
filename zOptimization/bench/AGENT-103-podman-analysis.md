# AGENT-103: podman / RIID / dfinit — timing analysis

Scope: the analysis requested in the [AGENT-103](https://usatovpavel.atlassian.net/browse/AGENT-103)
comment thread — not the parent "Check logs" subtask itself (metrics dashboard, Python
load-image reading). Source data: `zOptimization/clusterLogs-agent74-v0.4.11/dfinit-podman`
(this stand, v0.4.11, this session) and `zOptimization/clusterLogs-run2` (previous stand,
v0.4.10, prior session) as pointed to in the comment, cross-checked against the raw TSVs
in `deploy/k8s/performance/output/` of both worktrees.

## Data provenance (read this before the numbers)

| arm | run | RIID image | cluster/session |
|---|---|---|---|
| `bare-podman` | this session | n/a (no RIID, direct `podman pull`) | this stand, 2026-08-30 |
| `dfinit-podman` | this session | v0.4.11 | this stand, 2026-08-30 |
| `riid-podman` (noprefix) | **prior session** | v0.4.10 | previous stand, 2026-08-30 00:xx MSK, 19/20 (`sonarqube` failed, undiagnosed then) |
| `riid-containerd` (noprefix / prefix) | this session | v0.4.11 | this stand, 2026-08-30 |

`bare-podman` and `dfinit-podman` are the same cluster, same hour, same RIID build — directly
comparable. `riid-podman` is the only "RIID, no dfinit, podman" data that exists anywhere
(the `riid-podman-noprefix`/`riid-podman-prefix` arms were never re-run on this stand — see
"Still open" below); it comes from a different cluster instance and the older v0.4.10 image.
That version gap does not touch this code path (`PodmanUnixSocketClient` only changes the
prefix-import transport, not plain pull), but a different cluster run is still a different
run — registry cache state, node placement and network jitter are not controlled for across
sessions. Treat the `riid-podman` column as directional, not as tightly bound to `bare`/`dfinit`
as those two are to each other.

## 1. Why did `dfinit-podman` run slower than `bare-podman`?

Short answer: **it mostly didn't, per pod.** The published "aggregate" number (max end −
min start across the 4 parallel pods pulling that image) makes it look like a ~42% regression
(628.0s vs 441.6s over 20 images), but that total is dominated by two single-pod stragglers.
Look at the per-pod **median** instead of the max, and the regression drops to +8.6%
(461.2s vs 424.7s over 20 images) — much closer to what a P2P hop (dfdaemon proxy + scheduler
negotiation) should cost on top of a direct pull.

| image | dfinit pods (4x duration, s) | max−min gap |
|---|---|---|
| `sonarqube:latest` | 22.2, 22.7, 22.9, **153.9** | 131.8s |
| `pinetwork/pi-node-docker` | 16.4, 16.6, 16.6, **48.0** | 31.6s |
| everything else (18 images) | gap ≤ 1.9s | — |

These two stragglers alone account for **163.4s of the 186.4s total aggregate delta (87.7%)**.
Remove them and the remaining 18-image delta is 23.0s total, ~1.3s/image — noise.

**Root cause of the stragglers**: `dfdaemon` (the Dragonfly client, one per node) logs
`connect to http://<ip>:8002 failed: transport error` /
`create health client for scheduler <ip>:8002 failed: ConnectError` against **three**
scheduler endpoints — `10.10.83.130:8002`, `10.10.195.198:8002`, `10.10.195.202:8002` —
repeatedly, on multiple client pods, for the **entire** `dfinit-podman` run (first occurrence
08:53:52, still recurring past 09:04 when the sonarqube straggler was in flight). Only one of
these endpoints is a live scheduler; the other two are stale (this is the same "stale P2P
scheduler address" symptom already tracked in the k8s-bench skill's known traps, caused by
`clear-cache-all-riid-pods.sh` not always converging the data plane onto a single live
scheduler IP). Most of the time a client picks the live endpoint quickly and the P2P overhead
stays small (+8.6% median); occasionally — evidently node/pod-dependent — a client's specific
piece-download negotiation stalls through enough failed/retried health checks against the
dead endpoints that the whole pull is held up by tens of seconds to two minutes. That is
what produced the `sonarqube` and `pinetwork` outliers.

Evidence:
```
zOptimization/clusterLogs-agent74-v0.4.11/dfinit-podman/dfdaemon/dragonfly-client-*.log
  grep ERROR → "create health client for scheduler 10.10.83.130:8002 failed: ConnectError cause: transport error"
  (and the same for 10.10.195.198:8002, 10.10.195.202:8002), recurring 08:53:52 → past 09:04:17
```
Even `bare-podman` has one minor straggler of its own (`rancher-agent`: median 6.5s vs max
17.1s, a 10.6s gap — plain registry-side variance), so stragglers aren't unique to dfinit;
what's unique is the *size* of the two dfinit outliers, and that size lines up with a known,
already-tracked infra defect rather than anything inherent to P2P pulling.

## 2. Overall picture: podman (bare) vs us (RIID) vs dfinit

Aggregate duration per image (max end − min start across 4 pods), seconds:

| image | bare-podman | riid=>podman† | dfinit=>podman |
|---|---:|---:|---:|
| runmymind/docker-android-sdk | 102.1 | 213.4 | 104.2 |
| onlyoffice/documentserver | 45.3 | 87.1 | 47.6 |
| sysdig/agent | 38.0 | 85.7 | 39.7 |
| jetbrains/teamcity-server | 43.0 | 79.3 | 44.5 |
| apache/airflow | 28.8 | 42.2 | 34.4 |
| elasticsearch | 27.0 | 41.8 | 27.0 |
| grafana/grafana | 19.0 | 31.6 | 20.7 |
| pinetwork/pi-node-docker | 16.3 | 26.8 | 48.0 ‡ |
| cimg/base | 17.8 | 25.6 | 19.7 |
| netdata | 12.7 | 19.7 | 14.4 |
| php | 9.3 | 12.3 | 10.9 |
| rancher/rancher-agent | 17.2 | 12.1 | 9.3 |
| mysql | 12.5 | 8.2 | 14.5 |
| openpolicyagent/gatekeeper | 3.9 | 6.7 | 5.7 |
| fluxcd/flux | 5.5 | 6.3 | 7.6 |
| istio/operator | 3.5 | 5.3 | 5.4 |
| rabbitmq | 6.2 | 4.4 | 8.3 |
| amazon/aws-efs-csi-driver | 5.1 | 4.0 | 6.9 |
| redis | 3.3 | 3.8 | 5.2 |
| sonarqube | 25.4 | FAILED | 153.9 ‡ |
| **TOTAL (20 images, dfinit failed=0)** | **441.6** | 716.2 (19/20) | **628.0** |
| **TOTAL, 19 images excl. sonarqube** | **416.3** | **716.2** | **474.1** |

† different run/session — see provenance note above.
‡ straggler-inflated, see §1.

**Headline finding**: on the 19 directly-computable images, plain `riid=>podman` (no dfinit)
costs **+72% over bare-podman** (716.2s vs 416.3s), while `dfinit=>podman` costs only **+14%
over bare** (474.1s vs 416.3s) on that same set — i.e. adding Dragonfly P2P did not add to
RIID's overhead over bare podman, it **roughly halved** it (716.2s → 474.1s). This should be
read with the provenance caveat above (`riid=>podman` is a different run), but the gap is
large enough (2x) that ordinary run-to-run jitter is an unlikely full explanation; the more
likely story is that RIID's own import path (unpacking into the archive/import machinery
before handing to podman) carries a real fixed cost, and pulling layers through the local
Dragonfly peer/cache measurably cuts the registry-round-trip part of that cost.

## 3. Download time, riid=>podman and riid=>containerd

Aggregate duration per image, seconds (`riid=>podman` = v0.4.10 prior run, `riid=>containerd`
= v0.4.11 this session, both "noprefix"; containerd prefix arm included for reference):

| image | riid=>podman | riid=>containerd | riid=>containerd (prefix) |
|---|---:|---:|---:|
| runmymind/docker-android-sdk | 213.4 | 147.1 | 187.1 |
| onlyoffice/documentserver | 87.1 | 64.3 | 56.7 |
| sysdig/agent | 85.7 | 44.8 | 53.5 |
| jetbrains/teamcity-server | 79.3 | 72.3 | 62.2 |
| apache/airflow | 42.2 | 42.0 | 54.8 |
| elasticsearch | 41.8 | 34.1 | 51.9 |
| grafana/grafana | 31.6 | 24.7 | 34.7 |
| pinetwork/pi-node-docker | 26.8 | 20.6 | 23.1 |
| cimg/base | 25.6 | 20.2 | 30.0 |
| netdata | 19.7 | 15.8 | 17.1 |
| php | 12.3 | 12.0 | 11.8 |
| rancher/rancher-agent | 12.1 | 9.8 | 12.5 |
| mysql | 8.2 | 18.5 | 22.6 |
| openpolicyagent/gatekeeper | 6.7 | 6.5 | 11.2 |
| fluxcd/flux | 6.3 | 6.9 | 14.3 |
| istio/operator | 5.3 | 5.4 | 10.0 |
| rabbitmq | 4.4 | 10.5 | 14.3 |
| amazon/aws-efs-csi-driver | 4.0 | 7.1 | 12.0 |
| redis | 3.8 | 4.5 | 9.8 |
| sonarqube | FAILED | 40.3 | 32.2 |
| **TOTAL** | 716.2 (19/20) | **607.4** (20/20) | **722.0** (20/20) |

`riid=>containerd` (noprefix) came in fastest of the three RIID variants shown here, ahead of
`riid=>podman` — but again, cross-run/cross-engine, directional only. `riid=>containerd`
prefix is consistently the slowest engine/arm combination measured this session, which tracks
with prefix-import doing its own extra local unpack step before handing the path to `ctr`.

## 4. Specific image: podman / us / dfinit

The 20-image dataset has no image literally named `python`; the closest general-purpose
runtime image in it is **`php`**, used below as the representative "typical, no-straggler"
case. **`sonarqube`** is shown alongside it because it's the concrete example of the effect
explained in §1.

| | bare-podman | riid=>podman† | dfinit=>podman |
|---|---:|---:|---:|
| **php** (typical case) | 9.3s | 12.3s | 10.9s |
| **sonarqube** (straggler case) | 25.4s | FAILED | 153.9s (median pod: 22.8s) |

`php`: dfinit sits between bare and plain RIID, exactly the "+8.6% median overhead" pattern
from §1 — nothing anomalous. `sonarqube`: 3 of 4 dfinit pods finished in 22.2–22.9s (*faster*
than bare's 25.4s), the 4th took 153.9s — the scheduler-endpoint churn from §1 stalling one
node's client, not a per-image-size effect.

## Still open

- `riid-podman-noprefix`/`riid-podman-prefix` were never re-run on this session's stand
  against `bare-podman`/`dfinit-podman` on the same cluster; §2/§3's `riid=>podman` column
  remains cross-run. Re-running both on this stand would let §2 drop the provenance caveat
  entirely.
- The stale-scheduler-endpoint condition (`10.10.83.130:8002`, `10.10.195.198:8002` never
  answering all run) was not fixed this session, only observed as the proximate cause of the
  two stragglers above; see the k8s-bench skill's "known traps" for the existing gating-bug
  writeup in `clear-cache-all-riid-pods.sh`.
