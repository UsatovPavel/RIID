#!/bin/bash
# Установка зависимостей для Minikube + Dragonfly (kubectl, socat, crictl, CNI)
# Запускать один раз перед первым использованием minikube-dragonfly
set -e

echo ">>> Установка kubectl и socat..."
sudo apt-get install -y socat apt-transport-https ca-certificates curl gnupg
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.35/deb/Release.key | sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
sudo chmod 644 /etc/apt/keyrings/kubernetes-apt-keyring.gpg
echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.35/deb/ /' | sudo tee /etc/apt/sources.list.d/kubernetes.list
sudo chmod 644 /etc/apt/sources.list.d/kubernetes.list
sudo apt-get update
sudo apt-get install -y kubectl

echo ">>> Установка crictl..."
VERSION="v1.30.0"
wget -q "https://github.com/kubernetes-sigs/cri-tools/releases/download/${VERSION}/crictl-${VERSION}-linux-amd64.tar.gz"
sudo tar -xzf "crictl-${VERSION}-linux-amd64.tar.gz" -C /usr/local/bin
rm -f "crictl-${VERSION}-linux-amd64.tar.gz"

echo ">>> Установка CNI plugins..."
CNI_PLUGIN_VERSION="v1.5.1"
CNI_PLUGIN_TAR="cni-plugins-linux-amd64-$CNI_PLUGIN_VERSION.tgz"
CNI_PLUGIN_INSTALL_DIR="/opt/cni/bin"
curl -LO "https://github.com/containernetworking/plugins/releases/download/$CNI_PLUGIN_VERSION/$CNI_PLUGIN_TAR"
sudo mkdir -p "$CNI_PLUGIN_INSTALL_DIR"
sudo tar -xf "$CNI_PLUGIN_TAR" -C "$CNI_PLUGIN_INSTALL_DIR"
rm -f "$CNI_PLUGIN_TAR"

echo ">>> Установка завершена. Для driver=none также нужны: cri-dockerd, containernetworking-plugins"
