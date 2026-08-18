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
if ! curl -sk --noproxy '*' --max-time 5 -o /dev/null https://10.96.0.1:443/version; then
  if ! ip rule show | grep -q "to 10.96.0.0/12 lookup main"; then
    log "ClusterIP недоступен — возвращаю 10.96.0.0/12 в основную таблицу маршрутов"
    sudo ip rule add to 10.96.0.0/12 lookup main priority 32763
  fi
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
helm install --wait --timeout 15m --create-namespace --namespace "$NAMESPACE" \
  dragonfly dragonfly/dragonfly --version "$CHART_VERSION" -f "$VALUES" --set seedClient.replicas=1

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
