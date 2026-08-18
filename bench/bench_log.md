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
ноде — ранее скачаны для 

## 5. Записи замеров

Сценарий и методика — `Plan.md`, стенд бенча — `bench/`, run_id
`20260818T111946Z`. Python скачан **целиком через Dragonfly P2P**
(`sources=p2p:8`, ни одного `cache`/`registry` среди 8 слоёв), RIID-кэш
очищен рестартом демона перед прогоном (§3 плана) — `dirty=0`.

- MR #NN c725b05 — baseline-замер (handoff-бенч AGENT-72), до варианта 1 из §3 | стенд: Linux 7.0.0-29-generic / podman 5.7.0 / java openjdk 25.0.3
  library/python:latest 395.2MiB N=1 dragonfly-warm sources=p2p:8: handoff med 12.3s (layout 0.1 + import 12.3), wall med 13.0s, dl_end 0.5s
  verdict: baseline ; next: вариант 1 (`podman pull oci:` вместо `tar | podman load`) — ожидание: заметное снижение `handoff_import` (сейчас 12.3s из 12.3s handoff, т.е. почти весь handoff — это engine.import)
