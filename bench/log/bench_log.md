# Optimization: импорт образа в podman (engine.import)

## 1. Обнаруженный bottleneck

Источник данных: `zarchivePR18/unbound_calc/recreate_scenario/riid-6sxmh.log`,
trace_id `3ba2fbf0-1343-4e91-a412-30cb0478850e` (образ `riid/python:latest`,
cold recreate сценарий, нода `yasmina-node-kf24s`, 2026-06-14).

Разбор стадий (все `duration_ms` в JSON-логах кумулятивные от старта запроса,
14:32:56.695):

| Этап | Завершение (UTC) | Кумулятив. duration_ms | Чистое время этапа | Доля |
|---|---|---|---|---|
| manifest.fetch | 14:32:56.695 | 5 | ~0.005s | ~0% |
| source.select/fetch (cache, 3 общих слоя Debian-базы) | 14:32:56.696 | 0 | мгновенно | 0% |
| **P2P download** (5 уникальных слоёв через dfdaemon) | 14:33:01.203 | 4506 | **~4.51s** | 22% |
| **archive.build** (сборка OCI layout, без tar) | 14:33:01.424 | 4728 | **~0.22s** | 1% |
| **engine.import** (`tar \| podman load` в runtime) | 14:33:16.706 | 20011 | **~15.28s** | **76%** |
| **Итого** | | **20011** | **20.01s** | 100% |

Сравнение с прямым podman pull (без RIID/P2P) для того же образа,
`deploy/k8s/performance/output/10simulatiously/prep-podman.tsv`, строка
`riid-6sxmh`: **26737 ms** (полное скачивание+импорт напрямую из registry).

Вывод: **узкое место — не P2P-доставка слоёв и не сборка OCI layout в RIID**
(оба вместе ~4.7s, 23%), а финальный шаг **`engine.import`** — импорт уже
готового локального OCI layout в podman runtime, который занимает 15.28s
(76% полного времени). Три общих базовых слоя Debian (взятые из кэша на
ноде — ранее скачаны для `riid/debian:trixie-backports` и
`riid/golang:latest`, см. trace_id `668ba151...` и `f5d082a7...`)
**всё равно повторно распаковываются podman-ом** в каждом поде, несмотря
на то что сетевой fetch для них был пропущен (`cache hit`, 0ms).

Код, выполняющий этот шаг: `PodmanRuntimeAdapter.importOciLayoutDirectory()`
(`src/main/java/riid/runtime/adapter/PodmanRuntimeAdapter.java:49-62`):
```
tar -cf - -C <ociLayoutRoot> . | podman load -q
```
— готовый на диске OCI layout (уже без tar, см. `OciArchiveBuilder`,
лог "OCI layout build completed (stream import, no tar file)") заново
упаковывается в tar-поток и передаётся в stdin `podman load`, который
внутри снова его распаковывает на overlayfs (через `fuse-overlayfs`,
см. `deploy/k8s/src/riid/storage.conf`).

## 2. Записи замеров

Результат замера — одно число, `handoff` (медиана): всё остальное ниже —
разложение и контекст.

### Handoff: 12.3s

Сценарий и методика — `bench/README.md`, стенд бенча — `bench/`, run_id
`20260818T111946Z`. Python скачан **целиком через Dragonfly P2P**
(`sources=p2p:8`, ни одного `cache`/`registry` среди 8 слоёв), RIID-кэш и
podman очищены перед прогоном (см. `bench/bench.py::do_run`) — `dirty=0`.

- MR #59 92d4eac — baseline-замер (handoff-бенч AGENT-72) | стенд: Linux 7.0.0-29-generic / podman 5.7.0 / java openjdk 25.0.3
  library/python:latest 395.2MiB N=1 dragonfly-warm sources=p2p:8: handoff med 12.3s (layout 0.1 + import 12.3), wall med 13.0s, dl_end 0.5s
  verdict: baseline ; next: заменить `tar -cf - | podman load` на `podman pull oci:` в `PodmanRuntimeAdapter.importOciLayoutDirectory()` — ожидание: заметное снижение `handoff_import` (сейчас 12.3s из 12.3s handoff, т.е. почти весь handoff — это engine.import)
- AGENT-73 d1ad66e — `podman pull oci:` наивный (Docker schema2 внутри OCI index, без ref-суффикса/tag) | стенд: тот же
  library/python:latest 395.2MiB N=1 dragonfly-warm sources=p2p:8: handoff med 17.1s (layout 0.1 + import 17.0), wall med 17.9s, dl_end 0.6s
  verdict: regression (+47% import к baseline) ; причина (debug-лог podman): `containers/image` copy engine прогоняет manifest candidate-list + per-blob reuse-check, которых `podman load` не делает ; next: попробовать нормализовать media type манифеста на OCI перед записью index.json — убрать candidate-list из-за схемы
- AGENT-73 d1ad66e — `podman pull oci:` + нормализация media type манифеста в OCI (`OciArchiveBuilder.normalizeToOciMediaTypes`) | стенд: тот же
  library/python:latest 395.2MiB N=1 dragonfly-warm sources=p2p:8: handoff med 15.4s (layout 0.1 + import 15.3), wall med 16.1s, dl_end 0.5s
  verdict: regression (+32% import к baseline), лучше наивного варианта на ~1/3 разрыва, но хуже baseline ; candidate-list остаётся и для чистого OCI-манифеста (не только для docker schema2) — оставшийся разрыв не объясняется схемой ; вариант 1 отклонён, откат на `tar | podman load` (детали и root-cause — `zOptimization/PlanPodmanOciPull.md`, `ResearchPodmanEngineSpecific.md`)
- AGENT-89 (по 0a2d237) — `PodmanRuntimeAdapter.prefersOciLayoutStreamImport()=false`: писать реальный tar-файл
  (`OciArchiveBuilder.withArchive`, уже существовавший, но неиспользуемый путь) + `podman load -q -i <path>`
  вместо `tar -cf - | podman load -q` на stdin — гипотеза: `-i <path>` пропускает
  `io.Copy(tempfile, stdin)` внутри `podman load` (`cmd/podman/images/load.go`, подтверждено чтением
  исходников), должно снять часть handoff | стенд: AI_Box VM, podman 4.9.3, `library/python:latest`.
  Методика: 4 независимых раунда, каждый на свежей (`helm uninstall` → `helm install` → overlay)
  Dragonfly-инсталляции, по одному замеру (`--iterations 0`, только seed) на вариант за раунд —
  исключает накопление P2P-контента/contention между замерами, `sources=p2p:7,registry:1` во всех 8

  | раунд | stdin (`true`) handoff | `-i` (`false`) handoff | разница |
  |---|---|---|---|
  | 1 | 20646ms | 18944ms | −1702ms |
  | 2 | 18774ms | 18134ms | −640ms |
  | 3 | 19248ms | 18294ms | −954ms |
  | 4 | 20025ms | 18639ms | −1386ms |

  среднее: stdin 19673ms (σ≈718) vs `-i` 18503ms (σ≈313) — `-i` быстрее **в 4 из 4** раундов, в среднем
  на 1170ms (~6%), разброс почти вдвое меньше
  verdict: эффект реальный и стабильный (не шум) — но кратно меньше исходной гипотезы про "9.2s residual"
  (`SessionSummary_1Hypothesis.md §7`); `import` короче в среднем на 1607ms (19114→17507ms), `layout`
  дороже на 436ms (560→996ms, из-за появившейся синхронной записи tar-файла) — итоговая чистая экономия
  1170ms соответствует разнице handoff ; **принято**, `prefersOciLayoutStreamImport()` возвращён в
  `false` ; next: остаток разрыва с dfinit по-прежнему объясняется в основном архитектурным барьером
  fetch/unpack (§5), не этим фактором

### RIID+Dragonfly vs. `dfinit`-mirrored plain `podman pull` (1.Hypotesis, AGENT-89 продолжение)

86df374 — оба плеча на одной свежей (cold) инсталляции Dragonfly, `library/python:latest` (1.14GB local),
образ на Selectel (`cr.selcloud.ru`) | стенд: AI_Box VM, Linux 6.8.0-138-generic, podman 4.9.3, minikube
v1.38.1, 1 нода / 1 seed-client. `dfinit`-плечо измерено только после фикса auth.json на mirror-хост и
`dragonfly-run-tmpfs` sizeLimit временно поднятым до 4Gi (позже откачен обратно на исходный `2Gi` —
см. `changelogEnv.md`, сам sizeLimit не был реальной причиной ENOSPC, это отдельный неисправленный
баг с конфликтом volume-монтов; без первого фикса — auth.json — P2P у dfinit не включается вовсе — см.
`zOptimization/SessionSummary_1Hypothesis.md` §6-7). Источники: `bench/results/20260819T100730Z.tsv`
(RIID), `bench/dfinit/results/20260819T115738Z_dfinit-final.tsv` (dfinit).

| | RIID + Dragonfly | `podman pull` via `dfinit` mirror | Δ (dfinit vs RIID) |
|---|---|---|---|
| **cold** (первый pull) | 61320 ms (dl_end 38647 + handoff 22421) | 47826 ms | dfinit быстрее в 1.28× |
| **warm** (повтор) | 23074 ms (dl_end 881 + handoff 21874) | 12498 ms | dfinit быстрее в 1.85× |

verdict: исходная гипотеза "RIID в 2× быстрее podman pull" опровергнута на корректно настроенном
`dfinit` — быстрее в обеих фазах, не только на warm ; идеальное перекрытие fetch/import предсказывает
пол для dfinit на cold ≈ max(38647, 22421) = 38647ms, факт 47826ms — барьер синхронизации объясняет
~60% разрыва (13494 из 22421 возможных мс), остаток ~9.2s не объяснён перекрытием ; на warm dfinit
(12498ms) обгоняет даже голый import RIID (21874ms) — часть разрыва не про синхронизацию, а про то, что
`podman load` без `-i` (`PodmanRuntimeAdapter.importOciLayoutDirectory()`, stdin-пайп) лишний раз
материализует весь tar-поток на диск (`cmd/podman/images/load.go`), чего нативный `podman pull` не
делает ; next: измерить `PodmanRuntimeAdapter.importImage(Path)` (`-i <path>`, уже существует, не
используется в текущем pull-пути) изолированно — не требует смены архитектуры.
