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

## 2. Предложенные варианты оптимизации (на стороне RIID)

1. **Убрать `tar | podman load`, использовать `oci:` transport напрямую**
   (`podman pull oci:<ociLayoutRoot>[:<ref>]`) — тот же containers/image
   слой, что и в podman/skopeo, но без сборки промежуточного tar-потока
   и без новой бинарной зависимости.
2. **Native overlay вместо fuse-overlayfs** — убрать `mount_program` из
   `storage.conf`, если kernel ноды поддерживает unprivileged overlay
   mounts в user namespace пода; устраняет FUSE-прослойку при copy-up.
3. (Отклонён) **Additional image stores / composefs** — общий read-only
   слой-стор на ноду, чтобы общие базовые слои (Debian и т.п.)
   распаковывались один раз на ноду, а не в каждом поде повторно.
   Отклонён: нельзя улучшать реализацию за счёт кластера, утилита не
   должна тюниться под синтетический тест.
4. (Отклонён) **Hardlink вместо fs.copy в `OciArchiveBuilder.pullLayer()`**
   (`OciArchiveBuilder.java:253`) — сейчас при cache-hit всё равно идёт
   полное копирование байт блоба с диска на диск при сборке OCI layout,
   хотя кэш и layout обычно на одной ФС. Отклонён: кэш по архитектуре
   проекта обязан быть independent от runtime-слоя (Cache: сохраняет
   скачанные слои... отдаёт путь из кэша вместо повторной загрузки — это
   контракт, который не должен зависеть от того, что runtime-потребитель
   делает со своей копией).
5. (Рассмотрен и отклонён) `skopeo copy oci:<dir> containers-storage:<image>`
   — тяжелее варианта 1: добавляет отдельный бинарь, хотя решает ту же
   задачу через ту же библиотеку.

## 3. Выбранный вариант

**Вариант 1: заменить `tar -cf - | podman load` на `podman pull oci:<ociLayoutRoot>[:<ref>]`**
в `PodmanRuntimeAdapter.importOciLayoutDirectory()`, с последующим
`podman tag <digest-или-ref> <imageId>` при необходимости явного имени.

Причины выбора:
- Никакой новой зависимости — `podman` уже есть в образе.
- Устраняет лишний round-trip упаковки/распаковки tar (RIID уже хранит
  OCI layout как обычные файлы на диске).
- Самый низкий риск и стоимость (~0.5 дня разработки), полностью
  тестируется локально без кластера.

Риски, требующие проверки перед внедрением:
- Формат `index.json`, генерируемый `OciArchiveBuilder`, должен
  однозначно резолвиться (`org.opencontainers.image.ref.name` или
  единственный manifest) — иначе `podman pull oci:<dir>` без суффикса
  ref может отказать.
- После pull образ может получить имя по digest, а не желаемый tag —
  нужен доп. шаг `podman tag`.

## 4. Проверка (план)

TODO — реализация варианта 1 ещё не начата, это отдельный PR поверх бенча
из этого MR.

## 5. Записи замеров

Результат замера — одно число, `handoff` (медиана): всё остальное ниже —
разложение и контекст.

### Handoff: 12.3s

Сценарий и методика — `bench/README.md`, стенд бенча — `bench/`, run_id
`20260818T111946Z`. Python скачан **целиком через Dragonfly P2P**
(`sources=p2p:8`, ни одного `cache`/`registry` среди 8 слоёв), RIID-кэш и
podman очищены перед прогоном (см. `bench/bench.py::do_run`) — `dirty=0`.

- MR #59 92d4eac — baseline-замер (handoff-бенч AGENT-72), до варианта 1 из §3 | стенд: Linux 7.0.0-29-generic / podman 5.7.0 / java openjdk 25.0.3
  library/python:latest 395.2MiB N=1 dragonfly-warm sources=p2p:8: handoff med 12.3s (layout 0.1 + import 12.3), wall med 13.0s, dl_end 0.5s
  verdict: baseline ; next: вариант 1 (`podman pull oci:` вместо `tar | podman load`) — ожидание: заметное снижение `handoff_import` (сейчас 12.3s из 12.3s handoff, т.е. почти весь handoff — это engine.import)
