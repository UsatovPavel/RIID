#!/bin/bash
# CI: установка зависимостей для minikube driver=none + Dragonfly
# Запускать на ubuntu-latest (GitHub Actions)
set -e

echo ">>> Installing cri-dockerd..."
CRI_DOCKERD_VERSION="v0.3.24"
curl -sLO "https://github.com/Mirantis/cri-dockerd/releases/download/${CRI_DOCKERD_VERSION}/cri-dockerd_0.3.24.3-0.debian-bookworm_amd64.deb"
sudo dpkg -i cri-dockerd_0.3.24.3-0.debian-bookworm_amd64.deb || sudo apt-get install -f -y
rm -f cri-dockerd_0.3.24.3-0.debian-bookworm_amd64.deb

echo ">>> Installing kubectl, socat, conntrack..."
sudo apt-get update
sudo apt-get install -y socat conntrack curl
curl -sLO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
rm -f kubectl

echo ">>> Installing crictl..."
VERSION="v1.30.0"
curl -sLO "https://github.com/kubernetes-sigs/cri-tools/releases/download/${VERSION}/crictl-${VERSION}-linux-amd64.tar.gz"
sudo tar -xzf "crictl-${VERSION}-linux-amd64.tar.gz" -C /usr/local/bin
rm -f "crictl-${VERSION}-linux-amd64.tar.gz"

echo ">>> Installing CNI plugins..."
CNI_VERSION="v1.5.1"
curl -sLO "https://github.com/containernetworking/plugins/releases/download/${CNI_VERSION}/cni-plugins-linux-amd64-${CNI_VERSION}.tgz"
sudo mkdir -p /opt/cni/bin
sudo tar -xf "cni-plugins-linux-amd64-${CNI_VERSION}.tgz" -C /opt/cni/bin
rm -f "cni-plugins-linux-amd64-${CNI_VERSION}.tgz"

echo ">>> Installing minikube..."
curl -sLO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
sudo install minikube-linux-amd64 /usr/local/bin/minikube
rm -f minikube-linux-amd64

echo ">>> Installing helm..."
curl -sL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

echo ">>> Starting cri-dockerd..."
sudo systemctl enable cri-docker.socket
sudo systemctl start cri-docker.socket
sleep 2

echo ">>> Starting minikube (driver=none)..."
sudo env HOME="$HOME" CHANGE_MINIKUBE_NONE_USER=true minikube start --driver=none

echo ">>> Minikube status:"
minikube status
