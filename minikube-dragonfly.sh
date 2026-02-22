#!/bin/bash
# Minikube + Dragonfly для RIID: --driver=none (bare-metal), Unix socket на хосте
# Требует: sudo, cri-dockerd, containernetworking-plugins (см. https://minikube.sigs.k8s.io/docs/drivers/none/)
set -e

## Устанваливаем что-то там для --driver-none
VERSION="v1.30.0"
wget -q "https://github.com/kubernetes-sigs/cri-tools/releases/download/${VERSION}/crictl-${VERSION}-linux-amd64.tar.gz"
sudo tar -xzf "crictl-${VERSION}-linux-amd64.tar.gz" -C /usr/local/bin
rm -f "crictl-${VERSION}-linux-amd64.tar.gz"


CNI_PLUGIN_VERSION="v1.5.1"
CNI_PLUGIN_TAR="cni-plugins-linux-amd64-$CNI_PLUGIN_VERSION.tgz"
CNI_PLUGIN_INSTALL_DIR="/opt/cni/bin"

curl -LO "https://github.com/containernetworking/plugins/releases/download/$CNI_PLUGIN_VERSION/$CNI_PLUGIN_TAR"
sudo mkdir -p "$CNI_PLUGIN_INSTALL_DIR"
sudo tar -xf "$CNI_PLUGIN_TAR" -C "$CNI_PLUGIN_INSTALL_DIR"
rm "$CNI_PLUGIN_TAR"


echo ">>> Остановка и пересоздание Minikube (--driver=none, can use all system resources)..."
minikube stop 2>/dev/null || true
minikube delete 2>/dev/null || true
sudo minikube start --driver=none

# kubeconfig после sudo попадает в /root — копируем для текущего пользователя
if [ -f /root/.kube/config ] && [ "$(id -u)" != "0" ]; then
  mkdir -p "$HOME/.kube"
  sudo cp /root/.kube/config "$HOME/.kube/config"
  sudo chown "$(id -u):$(id -g)" "$HOME/.kube/config"
fi

echo ">>> Добавление Helm-репозитория Dragonfly..."
helm repo add dragonfly https://dragonflyoss.github.io/helm-charts/ 2>/dev/null || true
helm repo update

echo ">>> Установка Dragonfly..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

helm uninstall dragonfly -n dragonfly-system 2>/dev/null || true
helm install --wait --timeout 15m --create-namespace --namespace dragonfly-system dragonfly dragonfly/dragonfly -f values.yaml

echo ""
echo ">>> Dragonfly установлен. Проверка:"
minikube kubectl -- get pods -n dragonfly-system
echo ""
echo ">>> Создание output-директории для P2P..."
sudo mkdir -p /var/run/dragonfly/output
sudo chmod 777 /var/run/dragonfly/output
echo ""
echo ">>> RIID подключается к dfdaemon: unix:///var/run/dragonfly/dfdaemon.sock"
