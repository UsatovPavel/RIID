# bench — handoff-бенчмарк RIID

Мерит **handoff**: передачу уже скачанного образа в контейнерный рантайм —
отрезок от момента, когда Dragonfly отдал последний слой, до завершения
операции. Именно этот отрезок в кластерном прогоне занимал 76% времени запроса
(разбор — `bench/log/bench_log.md` §1).

Тикеты: AGENT-72 (в эпике AGENT-71).

## Что считается

Из структурных JSON-логов демона по одному `trace_id` (все `duration_ms` в
логах кумулятивные от старта запроса):

```
t_dl_end       = max(duration_ms) по event=source.fetch
t_layout       = duration_ms у event=archive.build
t_end          = duration_ms у event=engine.import

handoff_layout = t_layout - t_dl_end     финализация OCI layout
handoff_import = t_end    - t_layout     импорт в движок
handoff        = t_end    - t_dl_end     ← оптимизируемая величина
```

Корреляция запроса с логами — заголовком `X-Trace-Id`, который бенч генерирует
сам (демон принимает его в `PullHttpHandler`). Правок в Java не требуется,
ничего не мокается: бенч только шлёт команды и читает логи.

Формула проверена на архивном трейсе
`zarchivePR18/unbound_calc/recreate_scenario/riid-6sxmh.log`
(trace `3ba2fbf0-…`, `python:latest`): парсер даёт 222 + 15283 = 15505 мс —
ровно те числа, что в `bench/log/bench_log.md`.

## Сценарий

Дословно из эпика: 10 образов 10–50 MB → демон → python 11-м; меряем только
python. **Одна итерация = весь этот тест целиком**, повторяется N раз ради
медианы:

1. `podman system prune -af` — одинаковое состояние podman на входе;
2. рестарт демона — это и есть чистка RIID-кэша (кэш создаётся на инстанс
   демона, `TempFileCacheAdapter`);
3. 10 образов из `images.py` (они же прогревают JIT перед замером);
4. `python:latest` — единственный измеряемый пулл;
5. разбор лога по его `trace_id`.

Кэш Dragonfly **не чистится никогда** — требование эпика: мы бенчим путь RIID
после Dragonfly, поэтому сетевое время должно быть вытеснено из отрезка.
`--iterations N` даёт N+1 прогонов теста: и перед итерацией 0, и перед каждой
следующей RIID-кэш и podman чистятся одинаково (шаги 1–2 выше) — разницы в
чистке нет. Разница только в состоянии Dragonfly и в том, что попадает в
агрегат:
  - итерация 0 (`phase=seed`) может застать Dragonfly холодным (сетевой
    back-to-source на первом проходе); её числа не идут в медиану, она нужна
    только чтобы прогреть Dragonfly (его кэш не чистится, слои остаются);
  - итерации 1..N (`phase=measure`) идут по уже тёплому Dragonfly — по ним и
    считается медиана `handoff`.

Итерация помечается `dirty`, если у измеряемого образа хоть один слой пришёл не
из `p2p`: RIID-кэш очищен, значит любой `cache`/`registry` означает, что
померили не тот путь.

## Запуск

```bash
./scripts/minikube-dragonfly.sh 1     # или bench/setup-env.sh — Dragonfly на 1 ноду
./gradlew shadowJar                   # build/libs/riid.jar
make -C bench check-env               # пререквизиты + доступность всех 11 образов
make -C bench bench                   # N=1 измеряемая итерация (плюс seed) — дефолт Makefile
make -C bench bench N=10
make -C bench record                  # запись для bench/log/bench_log.md
make -C bench env-report              # версии утилит
```

`--iterations` в `bench.py run` обязателен (дефолта нет намеренно — молчаливые
5 итераций по умолчанию легко потерять из виду); `N` в Makefile задаёт его,
по умолчанию `N=1`.

Реестр и учётные данные берутся из `deploy/k8s/config/.env`
(`RIID_REGISTRY_PROVIDER`, `RIID_SELECTEL_*` / `RIID_DOCKERHUB_*`); имена
репозиториев переводятся в нотацию реестра по правилам
`deploy/k8s/providers/registry/image/mapper-common.sh`. Провайдер можно
переопределить на время: `RIID_BENCH_PROVIDER=DOCKERHUB make -C bench bench`.

Секреты в файлы не пишутся: пароль уходит демону через
`--username` + `--password-env`, а `config/config.bench.yaml` содержит
`credentials: null` (реальные credentials демон получает только из CLI-флагов
при старте, см. комментарий в самом файле).

## Файлы

| Файл | Что |
|---|---|
| `Makefile` | единственная точка запуска |
| `bench.py` | оркестрация прогона: демон, podman, цикл итераций |
| `checks.py` | утилиты команд + резолв реестра + `check-env` |
| `tsv.py` | разбор JSON-лога по trace_id, формирование/чтение TSV |
| `record.py` | запись для `bench/log/bench_log.md` из последнего TSV |
| `config/images.py` | 10 образов + python, хардкод с обоснованием отбора |
| `config/config.bench.yaml` | шаблон конфига демона (p2p включён, отдельный сокет) |
| `config/settings.py` | пути и константы |
| `setup-env.sh` | root-часть стенда: podman, minikube, Dragonfly |
| `log/bench_log.md` | результаты замеров (append-only, git-tracked) |
| `log/changelogEnv.md` | установленные dev-утилиты и версии (git-tracked) |
| `results/` | `<utc>.tsv` + `daemon.log` на прогон (в `.gitignore`) |
