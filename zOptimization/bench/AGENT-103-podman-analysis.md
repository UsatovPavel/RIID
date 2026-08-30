# AGENT-103: podman / RIID / dfinit — timing analysis

Scope: the analysis requested in the [AGENT-103](https://usatovpavel.atlassian.net/browse/AGENT-103)
comment thread — not the parent "Check logs" subtask itself (metrics dashboard, Python
load-image reading). Source data: `zOptimization/clusterLogs-agent74-v0.4.11/dfinit-podman`
(this stand, v0.4.11, this session) and `zOptimization/clusterLogs-run2` (previous stand,
v0.4.10, prior session) as pointed to in the comment, cross-checked against the raw TSVs in
`deploy/k8s/performance/output/` of both worktrees.

## Data provenance

| arm | run | RIID image | cluster/session |
|---|---|---|---|
| `bare-podman` | this session | n/a (no RIID, direct `podman pull`) | this stand, 2026-08-30 |
| `dfinit-podman` | this session | v0.4.11 | this stand, 2026-08-30 |
| `riid-podman` (noprefix) | prior session | v0.4.10 | previous stand, 2026-08-29 ~22:00 UTC / 00:xx MSK, 19/20 |
| `riid-containerd` (noprefix / prefix) | this session | v0.4.11 | this stand, 2026-08-30 |

`bare-podman` and `dfinit-podman` are the same cluster, same hour, same RIID build — directly
comparable. `riid-podman` is the only "RIID, no dfinit, podman" data that exists anywhere; it
comes from a different cluster instance and the older v0.4.10 image (the version gap doesn't
touch this code path — `PodmanUnixSocketClient` only changes the prefix-import transport, not
plain pull — but a different run is still a different run). Treat that column as directional.

`riid-podman`'s one failure, `sonarqube` (exit 1, pod `riid-dtbgk`): **not** an application
bug. `riid-dtbgk.log` has zero entries for the sonarqube request — no `manifest.fetch`, nothing
— even though the other three pods (`riid-qpvgq`, `riid-pc7vh`, `riid-w4kl7`) logged full,
successful pulls for the same image at the same start time. `riid-dtbgk`'s log stream stops
dead at `21:53:18Z`, right after its *previous* image (`rancher-agent`) finished, one second
before the sonarqube request would have started, and the whole run's log export was captured
at `21:55:15Z` — "minutes before teardown" per `clusterLogs-run2/RESULTS.md`. That pod's node
went away mid-request as the stand's lifetime timer tore the cluster down, which is why the
bench driver only got a bare exit 1 back ~133s later with nothing behind it: no error to read
because the process that would have logged one was already gone.

## 1. Why does RIID (with or without dfinit) look slower than bare podman?

Two separate effects are stacked here; conflating them is what makes `dfinit-podman` look
worse than it is.

**Effect A — RIID's own pipeline is not what `bare-podman` runs, P2P or not.**
`bare-podman` is a raw `podman pull`. RIID instead builds a local OCI archive from the fetched
layers and then loads *that* into the runtime over the podman socket (`archive.build` →
`engine.import`) — a comparison against `bare-podman` is a comparison against a different,
strictly larger amount of work, independent of transport. Reading the actual per-request trace
confirms this dominates over any P2P cost. Two real pulls, from `clusterLogs-run2` (the only
export with this level of RIID-side detail — see "Still open"):

```
php (riid-qpvgq, 196 MB payload):
  manifest.fetch    6ms
  8 layers, cache/p2p hits, source.select 0-2536ms cumulative from request start
  archive.build     3280ms  (cumulative: P2P/cache phase is done by t+3.3s)
  engine.import     6591ms  <- its own step, not cumulative: 66% of the 9964ms total
  load.total        9964ms

sonarqube (riid-pc7vh, 1.1 GB payload):
  manifest.fetch    6ms
  cache/p2p hits, source.select up to 12282ms cumulative
  archive.build     21132ms (cumulative: P2P/cache phase done by t+21.1s)
  engine.import     23031ms <- its own step: 52% of the 44237ms total
  load.total        44237ms
```

`engine.import` — writing the archive into podman's storage over the socket — is the single
largest phase in both cases (52-66% of wall time) and does not care whether the bytes behind
it came from cache, P2P, or a cold registry pull; ADR-11 measured the same effect at 76% on a
cold request. **This is the real reason plain `riid=>podman` costs +72% over `bare-podman`
(716.2s vs 416.3s aggregate, 19 comparable images)** — most of that gap is RIID doing strictly
more work than `bare-podman` ever attempts, not a P2P tax.

**Effect B — within RIID, does dfinit help or hurt?** This is the fair, apples-to-apples
question, since both arms run RIID's full archive-build/engine.import pipeline: `dfinit=>podman`
(474.1s over the same 19 images) is **38% faster than plain `riid=>podman`** (716.2s), i.e.
Dragonfly P2P *reduces* RIID's overhead over bare podman (+72% → +14%), it does not add to it.
So: comparing `dfinit-podman` against `bare-podman` directly (which is what made it "look
slower") mixes both effects and blames P2P for a cost (Effect A) that has nothing to do with
P2P. Judged on the comparison P2P actually controls (Effect B), Dragonfly is a net win here,
not evidence the deployment is fundamentally wrong.

**What dfinit *does* cost, on top of Effect A**: within this session's `dfinit-podman` run, two
of twenty images (`sonarqube`, `pinetwork/pi-node-docker`) had one straggler pod each — 153.9s
and 48.0s respectively against three sibling pods finishing in the normal 16-23s range. These
two stragglers account for 163.4s of the 186.4s total aggregate delta over `bare-podman`
(87.7%); the other 18 images cost +23.0s combined (~1.3s/image, noise). Root cause, from
`dfdaemon` logs: `create health client for scheduler <ip>:8002 failed: ConnectError cause:
transport error` recurring against **three** scheduler endpoints throughout the whole run
(`10.10.83.130:8002`, `10.10.195.198:8002`, `10.10.195.202:8002`, first seen 08:53:52, still
recurring past 09:04). Only one is a live scheduler; the other two are stale — the same
"stale P2P scheduler address" symptom already tracked in the k8s-bench skill's known traps
(`clear-cache-all-riid-pods.sh` not always converging the data plane onto one live scheduler
IP). That is a real, fixable dfinit-side defect — but it is a control-plane bug in this
deployment's cache-clear script, not evidence that peer-to-peer transfer itself is the wrong
architecture; Effect B above shows the architecture paying off even with this bug present.

## 2. Overall picture: bare-podman vs riid=>podman vs dfinit=>podman

Aggregate totals (sum of per-image max-across-4-pods duration), seconds:

| arm | total | vs bare-podman |
|---|---:|---:|
| `bare-podman` (19 comparable images) | 416.3 | — |
| `riid=>podman` (v0.4.10, cross-run) | 716.2 | +72% |
| `dfinit=>podman` | 474.1 | +14% |
| `dfinit=>podman`, all 20 images incl. the 2 stragglers | 628.0 | +51% (straggler-inflated, see §1) |

`riid=>containerd` (this session, v0.4.11, noprefix): 607.4s over 20 images — for context,
between bare-podman and riid=>podman in absolute terms, but a different engine so not directly
stacked against the podman column above. `riid=>containerd` prefix: 722.0s, the slowest arm
measured this session (prefix-import's own extra local unpack step before handing the path to
`ctr`).

## 3. Specific image, from the logs: `php` (typical) and `sonarqube` (the failure/straggler case)

No image in the 20-image dataset is literally named `python`; `php` is the closest
general-purpose language-runtime image and is used below as that proxy.

**`php`, normal case** (aggregate seconds): bare-podman 9.3, riid=>podman† 12.3, dfinit=>podman
10.9. The full request trace (§1, `riid-qpvgq`) shows nothing unusual: 8 layers resolve via
cache/P2P inside 2.5s, `engine.import` then takes 6.6s — the dominant cost, unrelated to source.

**`sonarqube`, the two failure modes actually observed**, both from real logs, not aggregates:
- *v0.4.10 run (`riid=>podman`)*: 3/4 pods succeeded in 36.5-46.5s with the same
  cache/p2p→archive.build→engine.import shape as `php` (see §1's trace, `riid-pc7vh`); the 4th
  (`riid-dtbgk`) never logged a single event for this request — see "Data provenance" above,
  the cluster's teardown timer killed its node mid-request.
- *this session (`dfinit=>podman`)*: 3/4 pods succeeded in 22.2-22.9s (faster than bare
  podman's 25.4s); the 4th took 153.9s. This session's RIID pod logs contain no per-request
  detail (see "Still open"), so the direct RIID-side trace for this straggler isn't available;
  the `dfdaemon` scheduler-connection-error evidence in §1, which spans this pod's entire pull
  window, is the available explanation.

† different run/session — see provenance note above.

## Still open

- **This session's RIID pod logs carry no per-request detail.** `zOptimization/clusterLogs-agent74-v0.4.11/dfinit-podman/riid/*.log`
  each contain exactly one event (`request.start`, the CLI bootstrap) and nothing else — none
  of the `manifest.fetch`/`source.select`/`archive.build`/`engine.import` trace that
  `clusterLogs-run2`'s export has. All of §1/§3's log-level narrative had to be reconstructed
  from the older v0.4.10 run for that reason. Worth checking whether `export-cluster-logs.sh`'s
  pod selector or the daemon's own log routing changed between these two runs, since without
  that trace this session's stragglers can only be explained from the `dfdaemon` side.
- `riid-podman-noprefix`/`riid-podman-prefix` were never re-run on this session's stand against
  `bare-podman`/`dfinit-podman` on the same cluster; §1/§2's `riid=>podman` numbers remain
  cross-run. Re-running both on this stand would remove that caveat entirely.
- The stale-scheduler-endpoint bug in §1 (Effect B's residual cost) was not fixed this session,
  only diagnosed; see the k8s-bench skill's "known traps" for the existing gating-bug writeup
  in `clear-cache-all-riid-pods.sh`.
