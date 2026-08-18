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
