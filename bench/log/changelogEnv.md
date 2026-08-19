# changelogEnv — изменения окружения разработки

Закрывает требование эпика AGENT-71: «Side-effects: all development utils which
installed must be recorded». Каждая установка/изменение окружения — строка здесь.
Версии инфраструктуры Dragonfly берутся из
`deploy/k8s/config/imagelist/dockerhub.yaml` (`.infra`), а не выбираются на глаз.

Формат: дата — что — версия — как поставлено — зачем.

## 2026-08-18 — AGENT-72, стенд для handoff-бенчмарка

Машина: Ubuntu 26.04 LTS, kernel 7.0.0-29-generic, 22 vCPU, 61 GB RAM,
297 GB свободно. Исходно из нужного стояли только `curl 8.18.0`,
`java 25.0.3 (openjdk)`, `python3 3.14.4`, `make 4.4.1`, `jq 1.8.1`, `gh`.

### Установлено без root (в `~/.local/bin`)

| Утилита | Версия | Как | Зачем |
|---|---|---|---|
| kubectl | v1.36.3 | `dl.k8s.io/release/stable.txt` → бинарь | доступ к одноузловому кластеру с Dragonfly |
| minikube | v1.38.1 | `storage.googleapis.com/minikube/releases/latest` | одноузловой кластер (`driver=none`, см. ниже) |
| helm | v3.21.4+g813176c | `get-helm-3`, `USE_SUDO=false` | установка чарта Dragonfly |
| yq | v4.53.3 | GitHub release `mikefarah/yq` | `scripts/render-values-from-infra.sh` требует yq; без него падал `make -C deploy/k8s/providers generate-registry-image-lists` |
| crictl | v1.34.0 | GitHub release `kubernetes-sigs/cri-tools` | требование minikube `driver=none` |

### Требует root — вынесено в `bench/setup-env.sh`

Скрипт идемпотентный, ставит и поднимает:

| Компонент | Источник | Зачем |
|---|---|---|
| podman **5.7.0** (+ uidmap, fuse-overlayfs, slirp4netns) | apt | целевой рантайм RIID (`runtimeId: podman`), сам предмет замера; работает rootless, storage driver `overlay`, runtime `runc` |
| docker.io + **cri-dockerd v0.3.24** (+ socat, conntrack, ethtool, iptables) | apt + deb с GitHub | рантайм ноды для minikube `driver=none`; так же сделано в CI (`scripts/ci-dragonfly-setup.sh`) |
| CNI plugins **v1.5.1** → `/opt/cni/bin`, `/etc/cni/net.d` | GitHub release `containernetworking/plugins` | то же |
| kubectl / minikube / helm / crictl / yq продублированы в `/usr/local/bin` | `install` из `~/.local/bin` | minikube с `driver=none` внутри зовёт `sudo crictl`, а sudo подменяет PATH на `secure_path` и `~/.local/bin` не видит |
| `/var/run/dragonfly/output` (0777) | mkdir | путь вывода dfdaemon, из него RIID забирает слои |
| кластер minikube (1 нода, `driver=none`, docker+cri-dockerd) | `minikube start` | `driver=none` обязателен: только тогда сокет dfdaemon виден на хосте по `/var/run/dragonfly/dfdaemon.sock`, а не внутри контейнера-ноды |
| Dragonfly | helm chart `dragonfly-1.6.26`; manager/scheduler `v2.4.4-rc.1`, client/seed-client `v1.3.8` | версии из `.infra` в `imagelist/dockerhub.yaml`, рендер `scripts/render-values-from-infra.sh` |

### Замечания по окружению (не установки, но существенно)

- `yq` отсутствовал, из-за чего `make -C deploy/k8s/providers generate-registry-image-lists`
  падал с `yq not found`. После установки генерация доступна; список 10 образов
  для бенча выписан из тех же двух входов, которые джойнит генератор
  (`imagelist/dockerhub.yaml` + `presented_images_list_sizes.tsv`).
- Учётные данные реестра в `deploy/k8s/config/.env`:
  - `RIID_SELECTEL_USER`/`RIID_SELECTEL_TOKEN` — сначала давали **401** на
    `cr.selcloud.ru/auth`; после обновления токена (2026-08-18) preflight
    зелёный: все 11 образов бенча доступны как `cr.selcloud.ru/riid/*`
    (`http=200`). Это основной путь, `RIID_REGISTRY_PROVIDER=SELECTEL`.
  - `RIID_DOCKERHUB_USER`/`RIID_DOCKERHUB_TOKEN` → `auth.docker.io` отвечает
    **401 `personal access token is expired`**; анонимный доступ к Docker Hub
    при этом работает и все 11 образов там тоже подтверждены. Запасной путь на
    случай недоступности Selectel — `RIID_BENCH_PROVIDER=DOCKERHUB make -C bench bench`.
- Сборка `build/libs/riid.jar` требует учётных данных GitHub Packages для
  `ru.hse:java-dragonfly-image-puller:1.2.0` (`gpr.user`/`gpr.key` в
  `~/.gradle/gradle.properties` либо `GITHUB_ACTOR`/`GITHUB_PACKAGES_TOKEN`).
  Ключи лежали в `~/.gradle/.gradle.properties` — с лишней точкой в начале,
  такой файл Gradle не читает; переименован в `~/.gradle/gradle.properties`
  (права 600). Также в `apt` пришлось поставить docker: `podman` для сборки не
  используется, но нужен как целевой рантайм бенча.
- Java на машине — 25.0.3, проект собирается на toolchain 23
  (`build.gradle.kts`, свойство `javaVersion`); Gradle подтянет JDK 23 сам либо
  нужно указать `-PjavaVersion=25`.

## 2026-08-18 — починка стенда под кластер (одна нода)

Три проблемы, из-за которых Dragonfly не поднимался на этой машине, и минимальные
изменения под каждую.

| Проблема | Причина | Изменение |
|---|---|---|
| pod'ы и хост не видят ClusterIP (`10.96.0.1:443` timeout при живом `192.168.1.27:8443`) | VPN AmneziaWG (`awg0`, таблица 51820) с catch-all маршрутизацией уводит в туннель и service-CIDR: маршрута для `10.96.0.0/12` в `main` нет, поэтому правило `lookup main suppress_prefixlength 0` его не ловит | `sudo ip rule add to 10.96.0.0/12 lookup main priority 32763` — только service-CIDR, не персистентно; откат `ip rule del ...`. Pod-CIDR трогать не нужно: для него в `main` есть маршрут через `cni0` |
| `storage-provisioner` в CrashLoop, PVC вечно `Pending` | следствие предыдущего пункта; после `kubectl delete pod` minikube его не пересоздаёт (addon-под, не DaemonSet), `minikube addons enable` тоже не вернул | 4 статических hostPath-PV с `claimRef` (mysql/redis-master/redis-replica 8Gi, seed-client 100Gi) вместо динамического провижнинга |
| mysql и redis в CrashLoop: `Permission denied` на данных | контейнеры чарта бегут под uid 1001, а hostPath-каталоги kubelet создаёт как `root:root 0755` | тома вынесены в `/var/tmp/riid-bench-pv/*` с правами 777 (каталог на диске, world-traversable, в отличие от `$HOME` с 0750) |
| Dragonfly ставился с `latest` вместо версий из imagelist | `scripts/render-values-from-infra.sh` лежал в git с режимом `100644` — без `+x`; `make _helm-install` зовёт его как `./render-values-from-infra.sh`, получает `Permission denied` и из-за `;` в рецепте продолжает с пустым values-файлом (в CI ровно тот же баг) | режим исправлен на `100755`; чарт ставится с `--version 1.6.26` из `.infra.dragonfly.helm_chart.github_release_tag` |

Итоговые версии в кластере: чарт `dragonfly-1.6.26`, manager и scheduler
`v2.4.4-rc.1`, client и seed-client `v1.3.8` — как в
`deploy/k8s/config/imagelist/dockerhub.yaml`.

## 2026-08-18 — прогон 20260818T101019Z: 100% fallback на registry, чинили DNS

Первый реальный прогон (`daemon.log` этого run_id) не крашился, но у ВСЕХ
образов (seed и measure) `sources=registry`, `dirty=non_p2p:registry` —
Dragonfly ни разу не отдал слой. В логе `dragonfly-client`:
`download started failed by error: backend error: ` (пусто) на каждый blob.

Причина — не auth и не сеть до реестра, а DNS внутри кластера:
`dragonfly-client` поднят с `hostNetwork: true`, но `dnsPolicy:
ClusterFirstWithHostNet`, поэтому резолвит имена через CoreDNS
(`10.96.0.10`), а не через resolver хоста. CoreDNS форвардит на
`/etc/resolv.conf`, а на этой машине это upstream-список
systemd-resolved (`85.143.162.254`, `85.143.163.254`, `192.168.1.1`,
`127.0.0.1`) — все четыре нерабочие *из пода*: `85.143.x.x` — внутренний
DNS VPN-провайдера, резолвится только через специальный роутинг
systemd-resolved на хосте (не как обычный публичный IP), `127.0.0.1` —
loopback пода, а не хоста. Итог: `cr.selcloud.ru` не резолвится изнутри
пода → dfdaemon не может даже сделать stat-запрos → RIID ловит
`IOException` от P2P-executor и молча падает на прямой registry-клиент
(рабочий путь fallback, отчего прогон не крашился, а просто был "грязным").

Проверено: с `8.8.8.8`/`1.1.1.1` тот же под резолвит `cr.selcloud.ru`
нормально (`31.184.217.2`).

Изменение: `kubectl -n kube-system edit configmap coredns` —
`forward . /etc/resolv.conf` → `forward . 8.8.8.8 1.1.1.1`, затем
`kubectl rollout restart deployment coredns -n kube-system`. Правится
только форвард CoreDNS, hosts'овый `/etc/resolv.conf` и VPN-роутинг не
трогаются (те уже отдельно чинены выше). Не персистентно относительно
пересоздания кластера — если `minikube delete && minikube start`, нужно
повторить руками (кандидат на перенос в `bench/setup-env.sh`, пока не
сделано).

## 2026-08-18 — прогон 20260818T111004Z: после DNS всё равно 100% registry, чинили hardlink

После починки CoreDNS выше `dragonfly-client` стал резолвить `cr.selcloud.ru`
и реально начал качать (появились `source.fetch`/`source.select` события,
Authorization: Bearer долетал до dfdaemon), но `sources` всё ещё были
`registry` на каждом слое. В логе клиента:
`hard link "/var/lib/dragonfly/content/tasks/<id>" to
"/var/run/dragonfly/output/p2p-*.bin" failed: Invalid cross-device link (os
error 18)` — dfdaemon скачивает блок в свой storage.dir (по умолчанию
`/var/lib/dragonfly/`, это приватный overlay-слой контейнера, никуда не
смонтирован), а затем должен захардлинкать готовый файл в outputPath;
жёсткая ссылка требует одной файловой системы, а output был отдельным
hostPath-томом (`/var/lib/dragonfly/output` на хосте) — другое устройство.
dfdaemon логирует это как WARN и не production output-файл вовсе (RIID
получает `dfdaemon completed without output file` и молча фоллбечится на
прямой registry-клиент — снова "грязный" прогон без явного краша).

Изменение (`scripts/values.yaml`, секция `client`): убран отдельный
`extraVolumes`-том `dragonfly-output` (hostPath `/var/lib/dragonfly/output`);
вместо этого `client.config.storage.dir: /var/run/dragonfly/data/` — storage
переехал на тот же hostPath, что уже смонтирован под сокет
(`/var/run/dragonfly`, том `socket-dir`), так что `data/` (storage) и
`output/` — теперь просто два подкаталога ОДНОГО и того же примонтированного
хоста-пути, а значит одна файловая система и хардлинк проходит. Применено
через `helm upgrade dragonfly dragonfly/dragonfly --version 1.6.26 -f
<rendered-values>`, без сброса PVC (`mysql`/`redis`/`seed-client` не
трогались). Прогон `make -C bench bench N=1` после фикса: `sources=p2p:8,
dirty=0` на обеих итерациях (seed и measure) — Dragonfly P2P реально
работает end-to-end.

## 2026-08-19 — VPN bypass rule снова не работает: приоритет 100 больше не достаточен

`SERVICE_CIDR_RULE_PRIORITY=100` (см. запись выше про 32763 -> 100) перестал
помогать: `setup-env.sh` падал на той же проверке ClusterIP, хотя правило на
100 присутствовало (`ip rule show`: `100: from all to 10.96.0.0/12 lookup
main`). Причина — AmneziaWG стал ставить СВОЁ правило на приоритете **99**:
`99: not from all fwmark 0xca6c lookup 51820` — это catch-all по fwmark (ловит
весь трафик без метки, включая наш к `10.96.0.1`), и приоритет 99 < 100,
поэтому оно матчится первым, до того как правило на 100 успевает сработать.
`ip route get 10.96.0.1` подтверждал: маршрут уходил в `awg0 table 51820`,
а не в `main`.

Также отдельно ловили `HOST_HOME_PERMISSION` (`~/.minikube` владеет root) при
проверке состояния кластера мимо самого скрипта, пока `setup-env.sh` ещё не
успел дойти до финального `chown` — это ожидаемое промежуточное состояние
скрипта (см. его собственный комментарий у `sudo chown -R ... $RUN_HOME/.kube
$RUN_HOME/.minikube`), не отдельный баг.

Фикс: `SERVICE_CIDR_RULE_PRIORITY` в `bench/setup-env.sh` поднят с `100` на
`90` (раньше 99, с запасом). Старое правило на 100 не мешает — приоритеты
независимы, `ip rule` допускает несколько правил на разные CIDR/приоритеты
одновременно; можно накопиться дубликатами при повторных прогонах, но не
ломает маршрутизацию (правило `100` теперь просто неактивно, забито `99`-м).
Если AmneziaWG в будущем снова сдвинет свой catch-all ниже 90 — тот же класс
проблемы, повторить диагностику через `ip rule show` + `ip route get
10.96.0.1`.

## 2026-08-19 — новая VM (AI_Box, чистый Ubuntu 24.04.4, VirtualBox): br_netfilter
не загружен — ClusterIP недоступен из pod'а без VPN

Стенд: свежая VBox VM `AI_Box` (см. `CLAUDE.md`), 6 vCPU, 11 GB RAM, чистый
Ubuntu 24.04.4, без VPN — т.е. не тот класс проблем, что в записях выше
(там всегда был AmneziaWG). `setup-env.sh` падал на той же строке
(`DNS registry из pod'а не заработал после CoreDNS patch`), но диагностика
показала другую причину:

- прямой pod-to-pod по реальному IP (`nc 10.244.0.4 53`) — работал;
- запрос к ClusterIP (`nc 10.96.0.10 53` / CoreDNS) — `connection timed out`
  из **любого** pod'а, включая `kube-system`, при этом `iptables-save`
  показывал корректные `KUBE-SERVICES`/`KUBE-SVC-*`/`KUBE-SEP-*` правила, и
  DNAT-счётчик у нужного правила рос (запрос доходил и матчился);
- `/proc/sys/net/bridge/bridge-nf-call-iptables` — **файла не было** (модуль
  `br_netfilter` не загружен). Без него bridged pod-to-pod трафик (оба pod'а
  в одной подсети моста) не проходит через netfilter/conntrack — запрос к
  ClusterIP доходит и DNAT'ится (это происходит на входе, через обычную
  маршрутизацию к gateway), а вот ответ от pod-получателя идёт обратно тем
  же L2-мостом напрямую, минуя conntrack, поэтому un-DNAT не происходит и
  клиент видит "timed out" на любом ClusterIP — CoreDNS в частности, отсюда
  и ложное впечатление "DNS не работает".

Фикс (добавлен в `setup-env.sh`, шаг 1, сразу после `iptables -P FORWARD
ACCEPT`, до создания CNI-моста): `sudo modprobe br_netfilter` +
`sysctl net.bridge.bridge-nf-call-iptables=1` (и `ip6tables` аналогично),
плюс персист через `/etc/modules-load.d/` и `/etc/sysctl.d/` (переживает
перезагрузку VM). После фикса `nc`/`nslookup` к `10.96.0.10:53` из pod'а —
успешны без CoreDNS-патча (сам патч на public DNS остался нейтральным
side-effect, не мешает).

Отличать от записи выше про AmneziaWG priority 90: там маршрут к
`10.96.0.0/12` вообще не попадал в `main` (симптом виден в `ip route get
10.96.0.1` на хосте); здесь маршрут корректный, `iptables`-правила
корректные, проблема ниже — в netfilter-хуках моста. Диагностика на будущее:
если `setup-env.sh` падает на DNS-проверке, сначала проверить `cat
/proc/sys/net/bridge/bridge-nf-call-iptables` (должно быть `1`, файл должен
существовать) — если файла нет, это новая VM без загруженного
`br_netfilter`, а не VPN-проблема.

## 2026-08-19 — AI_Box: dfinit-mirror никогда не доходил до P2P (auth) + tmpfs 2Gi мал для storage.dir

Продолжение разбора `1.Hypotesis.md` (после сброса лимита сессии). Задача: разобраться,
почему `dfinit`/dfdaemon в кластере на 1 ноде не даёт P2P-ускорения — оказалось, что
`dfinit` был "установлен" (registries.conf переписан корректно), но фактически P2P
никогда не участвовал в скачивании ни одного слоя. Два независимых, последовательно
найденных бага:

**1) auth.json не содержит credentials для mirror-хоста.** `containers/image`
резолвит credentials по точному ключу хоста (`127.0.0.1:4001/riid/<repo>`, а не
`cr.selcloud.ru/riid/<repo>`) — `podman login cr.selcloud.ru` кладёт запись только
под `cr.selcloud.ru`. Mirror-кандидат получает 401 на анонимный `/v2/`-пинг, не может
обменять его на bearer-токен ("unable to retrieve auth token: invalid username/password"),
и по стандартной `registries.conf` `[[registry.mirror]]` fallback-семантике podman
молча откатывается на прямой (non-mirror) пул — причём не только для этого пинга, а
для manifest'а и ВСЕХ блобов тоже. Отсюда в логах dfdaemon виден только безобидный
`/v2/`-passthrough и ни одной строки `blobs/sha256.*` — P2P просто никогда не пытался.
(Отдельно, безобидный и не связанный с этим шум в тех же логах: `ERROR ... invalid
HTTP method parsed` — это ожидаемый провал обязательного HTTPS-first пинга
`detectPropertiesHelper` в `containers/image`, TLS ClientHello попадает на
чистый-HTTP прокси-порт; сразу же корректно фоллбэчится на HTTP. Не баг, увёл
предыдущую итерацию расследования по ложному следу про keep-alive-парсинг.)

Фикс: дублировать запись `auth.json["auths"]["cr.selcloud.ru"]` под ключом
`"127.0.0.1:4001"` (тот же bearer/basic auth blob подходит, т.к. это один и тот же
registry за прокси). Внесено в `bench/dfinit/dfinit_bench.py` как `sync_mirror_auth()`,
вызывается один раз в начале `main()`. Проверено `podman --log-level=debug pull`
до/после — manifest и blob GET теперь реально идут через `127.0.0.1:4001`, и
dragonfly-client лог показывает `proxy_via_dfdaemon` → `download task succeeded`.

**2) `dragonfly-run-tmpfs` (`scripts/values.yaml`, `client.extraVolumes`) — `sizeLimit:
2Gi` мал.** `storage.dir` копит контент по всем скачанным образам за время жизни
Dragonfly-инсталляции (не чистится между pull'ами), поэтому прогон бенча по 11
образам (`bench/dfinit/dfinit_bench.py`, самый большой — `python:latest`, 1.14GB)
упирается в `No space left on device (os error 28)` на fallocate ещё до того, как
дойдёт до python — сам podman это видит просто как HTTP 500 на blob GET, что выглядит
как "P2P сломан", хотя на самом деле кончилось место. Увеличено до `4Gi` (с
комментарием в файле) — под лимитом памяти client-контейнера (8Gi), с запасом на
весь тестовый набор.

**Важное следствие**: раз P2P никогда не участвовал (баг №1), то ВСЕ прошлые числа
"dfinit" в `zOptimization/SessionSummary_AGENT-89.md` и в §2/§3/§5
`SessionSummary_1Hypothesis.md` этой сессии — это на самом деле замеры прямого
`podman pull` мимо Dragonfly, а не P2P-ускоренного пути. Корректный повторный замер —
после обоих фиксов, на свежей (cold) Dragonfly-инсталляции — см.
`zOptimization/SessionSummary_1Hypothesis.md` §7.

Побочный инцидент во время повторной установки: AI_Box (VirtualBox VM) неожиданно
упал в состояние `VMState=aborted` во время `helm install` сразу после увеличения
tmpfs sizeLimit (возможно совпадение с общей памятью хоста, возможно нет — не
доказано, что это связано с изменением, `sizeLimit` — это только верхний предел, не
резервирование). Восстановлено штатным `VBoxManage startvm AI_Box --type headless`,
SSH/port-forward пережили рестарт без переinicализации (см. `aibox_vm_access.md` в
памяти).
