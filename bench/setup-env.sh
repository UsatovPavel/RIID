#!/usr/bin/env bash
# Root-требующая часть стенда для handoff-бенчмарка (AGENT-72).
#
# Повторяет проверенный в CI бутстрап (scripts/ci-dragonfly-setup.sh +
# .github/workflows/ci.yml, шаги "Setup minikube (driver=none)" / "Install
# Dragonfly" / "Wait for dfdaemon socket") и добавляет podman — целевой рантайм
# RIID, которого в CI-бутстрапе нет.
#
# Бинарники ставятся в /usr/local/bin, а не в ~/.local/bin: minikube с
# driver=none внутри вызывает `sudo crictl`, а sudo подменяет PATH на
# secure_path и домашний каталог не видит.
#
# Запуск:  bash bench/setup-env.sh   (можно и через sudo — см. RUN_* ниже)
# Идемпотентен: повторный запуск ничего не ломает.
#
# Версии Dragonfly — из deploy/k8s/config/imagelist/dockerhub.yaml (.infra),
# рендер values — scripts/render-values-from-infra.sh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAMESPACE="dragonfly-system"
SERVICE_CIDR="10.96.0.0/12"
KUBERNETES_SERVICE_URL="https://10.96.0.1:443/version"
# Должно быть раньше policy rules, которые WireGuard/AmneziaWG обычно ставит.
# История: 32763 оказывалось после catch-all VPN rule (никогда не работало) ->
# 100 работало, пока VPN использовал catch-all на 32762 -> AmneziaWG стал
# ставить fwmark-правило "not from all fwmark <mark> lookup <table>" на
# приоритете 99, которое перехватывает трафик раньше 100 (у правил с fwmark-
# условием обычно нет "src"/более специфичного match, они матчат всё, что не
# помечено, поэтому наш bypass должен быть строго раньше). 90 запаса ради.
SERVICE_CIDR_RULE_PRIORITY="90"
CRI_DOCKERD_VERSION="v0.3.24"
CRI_DOCKERD_DEB="cri-dockerd_0.3.24.3-0.debian-bookworm_amd64.deb"
CNI_VERSION="v1.5.1"
CRICTL_VERSION="v1.34.0"
YQ_VERSION="v4.53.3"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# Кластер и kubeconfig должны принадлежать обычному пользователю, даже если
# скрипт запущен через sudo.
if [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != "root" ]; then
  RUN_USER="$SUDO_USER"
  RUN_UID="${SUDO_UID:-$(id -u "$SUDO_USER")}"
  RUN_GID="${SUDO_GID:-$(id -g "$SUDO_USER")}"
  RUN_HOME="$(getent passwd "$SUDO_USER" | cut -d: -f6)"
else
  RUN_USER="$(id -un)"
  RUN_UID="$(id -u)"
  RUN_GID="$(id -g)"
  RUN_HOME="$HOME"
fi
export PATH="/usr/local/bin:$PATH"

log() { printf '>>> %s\n' "$*"; }

check_pod_dns() {
  local pod_name="riid-dns-check"
  local status=0
  kubectl delete pod "$pod_name" -n kube-system --ignore-not-found --wait=false >/dev/null 2>&1 || true
  timeout 45s kubectl run "$pod_name" -n kube-system \
    --image=docker.io/busybox:latest --restart=Never --rm --attach --quiet \
    --command -- nslookup -type=A registry-1.docker.io >/dev/null 2>&1 || status=$?
  kubectl delete pod "$pod_name" -n kube-system --ignore-not-found --wait=false >/dev/null 2>&1 || true
  return "$status"
}

# Ставит бинарник в /usr/local/bin: сначала из ~/.local/bin пользователя,
# иначе скачивает.
install_binary() {
  local name="$1" url="$2"
  if [ -x "/usr/local/bin/$name" ]; then
    return 0
  fi
  if [ -x "$RUN_HOME/.local/bin/$name" ]; then
    sudo install -o root -g root -m 0755 "$RUN_HOME/.local/bin/$name" "/usr/local/bin/$name"
    return 0
  fi
  curl -sSLo "$WORK_DIR/$name" "$url"
  sudo install -o root -g root -m 0755 "$WORK_DIR/$name" "/usr/local/bin/$name"
}

# --------------------------------------------------------------------------- #
log "1/7 apt: docker + podman + зависимости minikube(driver=none)"
sudo apt-get update -qq
sudo apt-get install -y \
  docker.io socat conntrack ethtool iptables \
  podman uidmap fuse-overlayfs slirp4netns

log "2/7 cri-dockerd $CRI_DOCKERD_VERSION (driver=none + kubernetes >= 1.24)"
if ! systemctl list-unit-files | grep -q '^cri-docker\.socket'; then
  curl -sSLo "$WORK_DIR/$CRI_DOCKERD_DEB" \
    "https://github.com/Mirantis/cri-dockerd/releases/download/${CRI_DOCKERD_VERSION}/${CRI_DOCKERD_DEB}"
  sudo dpkg -i "$WORK_DIR/$CRI_DOCKERD_DEB" || sudo apt-get install -f -y
fi
sudo systemctl enable --now docker
sudo systemctl enable --now cri-docker.socket

# docker выставляет политику FORWARD DROP, из-за чего трафик CNI-моста
# дропается: pod'ы не видят ClusterIP (в т.ч. 10.96.0.1:443), storage-provisioner
# падает, PVC остаются Pending и Dragonfly не поднимается.
sudo iptables -P FORWARD ACCEPT

# На свежих хостах (например, только что созданной VM) модуль br_netfilter не
# загружен вообще — /proc/sys/net/bridge/bridge-nf-call-iptables отсутствует.
# Симптом: pod->ClusterIP запрос доходит и корректно DNAT'ится (видно в
# iptables-счётчиках KUBE-SVC-.../KUBE-SEP-...), но ответ от pod'а-получателя
# идёт обратно тем же L2-мостом напрямую (оба pod'а в одной подсети моста), не
# попадая в conntrack — без bridge-nf-call-iptables=1 такой bridged-трафик не
# проходит через netfilter, un-DNAT не происходит, клиент видит "connection
# timed out" на любой ClusterIP (в т.ч. CoreDNS 10.96.0.10:53), при этом прямой
# pod-to-pod (по реальному IP, не ClusterIP) работает нормально — это и отличает
# эту причину от VPN/routing-проблем ниже. Нужно ДО первого создания CNI-моста.
sudo modprobe br_netfilter
sudo sysctl -w net.bridge.bridge-nf-call-iptables=1 >/dev/null
sudo sysctl -w net.bridge.bridge-nf-call-ip6tables=1 >/dev/null
echo br_netfilter | sudo tee /etc/modules-load.d/br_netfilter.conf >/dev/null
printf 'net.bridge.bridge-nf-call-iptables=1\nnet.bridge.bridge-nf-call-ip6tables=1\n' | \
  sudo tee /etc/sysctl.d/99-k8s-bridge.conf >/dev/null

log "3/7 бинарники в /usr/local/bin"
install_binary kubectl "https://dl.k8s.io/release/$(curl -sSL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
install_binary minikube "https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64"
install_binary yq "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64"
if [ ! -x /usr/local/bin/crictl ]; then
  if [ -x "$RUN_HOME/.local/bin/crictl" ]; then
    sudo install -o root -g root -m 0755 "$RUN_HOME/.local/bin/crictl" /usr/local/bin/crictl
  else
    curl -sSLo "$WORK_DIR/crictl.tgz" \
      "https://github.com/kubernetes-sigs/cri-tools/releases/download/${CRICTL_VERSION}/crictl-${CRICTL_VERSION}-linux-amd64.tar.gz"
    sudo tar -xzf "$WORK_DIR/crictl.tgz" -C /usr/local/bin
  fi
fi
if [ ! -x /usr/local/bin/helm ]; then
  if [ -x "$RUN_HOME/.local/bin/helm" ]; then
    sudo install -o root -g root -m 0755 "$RUN_HOME/.local/bin/helm" /usr/local/bin/helm
  else
    curl -sSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 -o "$WORK_DIR/get-helm-3"
    sudo env HELM_INSTALL_DIR=/usr/local/bin USE_SUDO=false bash "$WORK_DIR/get-helm-3"
  fi
fi
for binary in kubectl minikube helm crictl yq; do
  command -v "$binary" >/dev/null || { echo "$binary не найден после установки" >&2; exit 1; }
done

log "4/7 CNI plugins $CNI_VERSION → /opt/cni/bin"
if [ ! -x /opt/cni/bin/bridge ]; then
  curl -sSLo "$WORK_DIR/cni.tgz" \
    "https://github.com/containernetworking/plugins/releases/download/${CNI_VERSION}/cni-plugins-linux-amd64-${CNI_VERSION}.tgz"
  sudo mkdir -p /opt/cni/bin
  sudo tar -xf "$WORK_DIR/cni.tgz" -C /opt/cni/bin
fi
sudo mkdir -p /etc/cni/net.d

log "5/7 каталог вывода dfdaemon"
sudo mkdir -p /var/run/dragonfly/output
sudo chmod 777 /var/run/dragonfly/output

# --------------------------------------------------------------------------- #
log "6/7 minikube: одна нода, driver=none (пользователь $RUN_USER)"
# driver=none обязателен: только тогда сокет dfdaemon виден на хосте по
# /var/run/dragonfly/dfdaemon.sock, а не внутри контейнера-ноды.
# Прерванный прогон оставляет ~/.minikube за root → HOST_HOME_PERMISSION.
sudo chown -R "$RUN_UID:$RUN_GID" "$RUN_HOME/.minikube" "$RUN_HOME/.kube" 2>/dev/null || true
sudo env HOME="$RUN_HOME" minikube stop >/dev/null 2>&1 || true
sudo env HOME="$RUN_HOME" minikube delete >/dev/null 2>&1 || true
sudo env HOME="$RUN_HOME" CHANGE_MINIKUBE_NONE_USER=true minikube start --driver=none

if [ ! -f "$RUN_HOME/.kube/config" ] && [ -f /root/.kube/config ]; then
  sudo mkdir -p "$RUN_HOME/.kube"
  sudo cp /root/.kube/config "$RUN_HOME/.kube/config"
fi
if [ ! -d "$RUN_HOME/.minikube" ] && [ -d /root/.minikube ]; then
  sudo cp -r /root/.minikube "$RUN_HOME/.minikube"
fi
sudo chown -R "$RUN_UID:$RUN_GID" "$RUN_HOME/.kube" "$RUN_HOME/.minikube" 2>/dev/null || true

export KUBECONFIG="$RUN_HOME/.kube/config"
kubectl cluster-info >/dev/null

# VPN с catch-all маршрутом (WireGuard/AmneziaWG и т.п.) уводит в туннель и
# service-CIDR: маршрута для 10.96.0.0/12 в main нет, поэтому правило
# "lookup main suppress_prefixlength 0" его не ловит. Тогда ClusterIP
# недоступен, storage-provisioner не видит API, PVC висят Pending и Dragonfly
# не поднимается. Правило добавляется только если проблема реально есть и
# затрагивает исключительно service-CIDR.
if ! curl -sk --noproxy '*' --max-time 5 -o /dev/null "$KUBERNETES_SERVICE_URL"; then
  if ! ip rule show | grep -Eq "^${SERVICE_CIDR_RULE_PRIORITY}:.*to ${SERVICE_CIDR//./\\.} lookup main$"; then
    log "ClusterIP недоступен — возвращаю $SERVICE_CIDR в main до VPN policy rules"
    sudo ip rule add to "$SERVICE_CIDR" lookup main priority "$SERVICE_CIDR_RULE_PRIORITY"
  fi

  for _ in $(seq 1 10); do
    curl -sk --noproxy '*' --max-time 2 -o /dev/null "$KUBERNETES_SERVICE_URL" && break
    sleep 1
  done
fi
if ! curl -sk --noproxy '*' --max-time 5 -o /dev/null "$KUBERNETES_SERVICE_URL"; then
  echo "ClusterIP Kubernetes всё ещё недоступен после VPN bypass rule" >&2
  ip rule show >&2
  ip route get 10.96.0.1 >&2 || true
  exit 1
fi

# На некоторых конфигурациях AmneziaWG upstream из /etc/resolv.conf доступны
# только через systemd-resolved хоста. Меняем CoreDNS лишь если A-запрос из
# реального pod'а не проходит; на обычной сети конфигурация остаётся нетронутой.
if ! check_pod_dns; then
  COREDNS_CORE_FILE="$(kubectl get configmap coredns -n kube-system -o jsonpath='{.data.Corefile}')"
  grep -q 'forward \. /etc/resolv\.conf' <<<"$COREDNS_CORE_FILE" || {
    echo "DNS registry из pod'а не работает, но CoreDNS не использует /etc/resolv.conf" >&2
    exit 1
  }
  log "CoreDNS: заменяю недоступные pod'ам host resolvers на public upstream"
  COREDNS_CORE_FILE="${COREDNS_CORE_FILE//forward . \/etc\/resolv.conf/forward . 8.8.8.8 1.1.1.1}"
  export COREDNS_CORE_FILE
  yq -n -o=json '{"data":{"Corefile":strenv(COREDNS_CORE_FILE)}}' > "$WORK_DIR/coredns-patch.json"
  kubectl patch configmap coredns -n kube-system --type merge --patch-file "$WORK_DIR/coredns-patch.json" >/dev/null
  kubectl rollout restart deployment coredns -n kube-system >/dev/null
  kubectl rollout status deployment coredns -n kube-system --timeout=2m
  check_pod_dns || { echo "DNS registry из pod'а не заработал после CoreDNS patch" >&2; exit 1; }
fi

# --------------------------------------------------------------------------- #
log "7/7 Dragonfly (версии из imagelist/dockerhub.yaml)"
# scripts/Makefile ставит чарт без --version, т.е. самый свежий; версия чарта
# тоже зафиксирована в imagelist (.infra.dragonfly.helm_chart.github_release_tag),
# поэтому ставим ровно её.
IMAGE_LIST="$REPO_ROOT/deploy/k8s/config/imagelist/dockerhub.yaml"
CHART_VERSION="$(yq e '.infra.dragonfly.helm_chart.github_release_tag' "$IMAGE_LIST" | sed 's/^dragonfly-//')"
[ -n "$CHART_VERSION" ] && [ "$CHART_VERSION" != "null" ] || { echo "нет версии чарта в $IMAGE_LIST" >&2; exit 1; }

helm repo add dragonfly https://dragonflyoss.github.io/helm-charts/ >/dev/null 2>&1 || true
helm repo update >/dev/null

VALUES="$WORK_DIR/dragonfly-values.yaml"
"$REPO_ROOT/scripts/render-values-from-infra.sh" "$VALUES"
yq e '.manager.image, .scheduler.image, .seedClient.image, .client.image' "$VALUES"

helm uninstall dragonfly -n "$NAMESPACE" >/dev/null 2>&1 || true
# seedClient.replicas из render-values-from-infra.sh идёт из deploy/k8s/config/config.yaml
# (dragonfly.seed_clients=3) — это топология прод-кластера (10 workers), не бенч-стенда.
# На одной ноде несколько seed-client реплик избыточны (качаем всё равно с одного узла),
# поэтому здесь фиксируем 1, не трогая общий config.yaml.
# Кластер каждый раз пересоздаётся, а benchmark требует сохранять Dragonfly
# cache только между итерациями одного запуска. emptyDir даёт именно такую
# семантику и не ставит весь Helm release в зависимость от minikube
# storage-provisioner (его CrashLoop раньше оставлял 4 PVC в Pending).
if ! helm install --wait --timeout 7m --create-namespace --namespace "$NAMESPACE" \
  dragonfly dragonfly/dragonfly --version "$CHART_VERSION" -f "$VALUES" \
  --set seedClient.replicas=1 \
  --set seedClient.persistence.enable=false \
  --set mysql.primary.persistence.enabled=false \
  --set redis.master.persistence.enabled=false \
  --set redis.replica.persistence.enabled=false; then
  echo ">>> helm install failed, dumping diagnostics:" >&2
  kubectl get pods,pvc -n "$NAMESPACE" -o wide >&2 || true
  kubectl get events -n "$NAMESPACE" --sort-by=.lastTimestamp >&2 || true
  kubectl logs -n kube-system storage-provisioner --tail=100 >&2 || true
  exit 1
fi

log "ожидание сокета dfdaemon"
for _ in $(seq 1 60); do
  if [ -S /var/run/dragonfly/dfdaemon.sock ]; then
    # RIID-демон работает не от root — сокет должен быть доступен пользователю.
    sudo chmod 666 /var/run/dragonfly/dfdaemon.sock || true
    log "сокет готов: /var/run/dragonfly/dfdaemon.sock"
    break
  fi
  sleep 2
done
if [ ! -S /var/run/dragonfly/dfdaemon.sock ]; then
  echo "!!! сокет dfdaemon не появился — RIID не сможет ходить в Dragonfly" >&2
  kubectl get pods -n "$NAMESPACE" -o wide >&2
  exit 1
fi

kubectl get pods -n "$NAMESPACE" -o wide
log "дальше: ./gradlew shadowJar && make -C bench check-env && make -C bench bench"
