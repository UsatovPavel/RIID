# bench — handoff-бенчмарк RIID

Мерит **handoff**: передачу уже скачанного образа в контейнерный рантайм —
отрезок от момента, когда Dragonfly отдал последний слой, до завершения
операции. Именно этот отрезок в кластерном прогоне занимал 76% времени запроса
(разбор — `Optimization.md` §1).

Тикеты: AGENT-72 (в эпике AGENT-71). План — `Plan.md` в корне репозитория.

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
ровно те числа, что в `Optimization.md`.

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
Итерация 0 (`phase=seed`) прогревает Dragonfly и в агрегат не входит.

Итерация помечается `dirty`, если у измеряемого образа хоть один слой пришёл не
из `p2p`: RIID-кэш очищен, значит любой `cache`/`registry` означает, что
померили не тот путь.

## Запуск

```bash
./scripts/minikube-dragonfly.sh 1     # или bench/setup-env.sh — Dragonfly на 1 ноду
./gradlew shadowJar                   # build/libs/riid.jar
make -C bench check-env               # пререквизиты + доступность всех 11 образов
make -C bench bench                   # N=5 измеряемых итераций (плюс seed)
make -C bench bench N=10
make -C bench record                  # 3 строки для Optimization.md
make -C bench env-report              # версии утилит
```

Реестр и учётные данные берутся из `deploy/k8s/config/.env`
(`RIID_REGISTRY_PROVIDER`, `RIID_SELECTEL_*` / `RIID_DOCKERHUB_*`); имена
репозиториев переводятся в нотацию реестра по правилам
`deploy/k8s/providers/registry/image/mapper-common.sh`. Провайдер можно
переопределить на время: `RIID_BENCH_PROVIDER=DOCKERHUB make -C bench bench`.

Секреты в файлы не пишутся: пароль уходит демону через
`--username` + `--password-env`, а `config.bench.yaml` содержит
`credentials: null`.

## Файлы

| Файл | Что |
|---|---|
| `Makefile` | единственная точка запуска |
| `bench.py` | прогон, разбор логов, TSV, агрегат |
| `images.py` | 10 образов + python, хардкод с обоснованием отбора |
| `config.bench.yaml` | шаблон конфига демона (p2p включён, отдельный сокет) |
| `setup-env.sh` | root-часть стенда: podman, minikube, Dragonfly |
| `results/` | `<utc>.tsv` + `daemon.log` на прогон (в `.gitignore`) |

Установленные утилиты и их версии фиксируются в `changelogEnv.md` в корне.
