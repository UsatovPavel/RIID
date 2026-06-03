#!/usr/bin/env bash
# Выполняется на Ubuntu 22.04 (YC ВМ). Вызывается через YandexCloud/Makefile: install-on-server / minikube-start-on-server
set -euo pipefail

cmd="${1:-install}"

start_minikube_none() {
  echo ">>> /etc/cni/net.d + minikube (driver=none)..."
  sudo mkdir -p /etc/cni/net.d
  sudo env HOME="${HOME}" CHANGE_MINIKUBE_NONE_USER=true minikube start --driver=none
  if [[ -f /root/.kube/config ]] && [[ "$(id -u)" -ne 0 ]]; then
    mkdir -p "${HOME}/.kube"
    sudo cp /root/.kube/config "${HOME}/.kube/config"
    sudo chown "$(id -u):$(id -g)" "${HOME}/.kube/config"
    if [[ -d /root/.minikube ]]; then
      sudo cp -r /root/.minikube "${HOME}/.minikube"
      sudo chown -R "$(id -u):$(id -g)" "${HOME}/.minikube"
    fi
  fi
  minikube status
}

do_install() {
  echo ">>> apt base..."
  sudo apt-get update
  sudo apt-get install -y ca-certificates curl gnupg wget apt-transport-https software-properties-common conntrack socat podman docker.io

  echo ">>> Docker..."
  sudo systemctl enable --now docker
  sudo usermod -aG docker "${USER}" || true

  echo ">>> Eclipse Temurin JDK 23..."
  sudo install -d /etc/apt/keyrings
  wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
  # shellcheck source=/dev/null
  . /etc/os-release
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb ${VERSION_CODENAME} main" | sudo tee /etc/apt/sources.list.d/adoptium.list
  sudo apt-get update
  sudo apt-get install -y temurin-23-jdk

  echo ">>> cri-dockerd (minikube driver=none)..."
  CRI_DOCKERD_VERSION="v0.3.24"
  curl -sLO "https://github.com/Mirantis/cri-dockerd/releases/download/${CRI_DOCKERD_VERSION}/cri-dockerd_0.3.24.3-0.debian-bookworm_amd64.deb"
  sudo dpkg -i cri-dockerd_0.3.24.3-0.debian-bookworm_amd64.deb || sudo apt-get install -f -y
  rm -f cri-dockerd_0.3.24.3-0.debian-bookworm_amd64.deb

  echo ">>> kubectl..."
  curl -sLO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
  sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
  rm -f kubectl

  echo ">>> crictl..."
  VERSION="v1.30.0"
  curl -sLO "https://github.com/kubernetes-sigs/cri-tools/releases/download/${VERSION}/crictl-${VERSION}-linux-amd64.tar.gz"
  sudo tar -xzf "crictl-${VERSION}-linux-amd64.tar.gz" -C /usr/local/bin
  rm -f "crictl-${VERSION}-linux-amd64.tar.gz"

  echo ">>> CNI plugins -> /opt/cni/bin..."
  CNI_VERSION="v1.5.1"
  curl -sLO "https://github.com/containernetworking/plugins/releases/download/${CNI_VERSION}/cni-plugins-linux-amd64-${CNI_VERSION}.tgz"
  sudo mkdir -p /opt/cni/bin
  sudo tar -xf "cni-plugins-linux-amd64-${CNI_VERSION}.tgz" -C /opt/cni/bin
  rm -f "cni-plugins-linux-amd64-${CNI_VERSION}.tgz"

  echo ">>> minikube..."
  curl -sLO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
  sudo install minikube-linux-amd64 /usr/local/bin/minikube
  rm -f minikube-linux-amd64

  echo ">>> helm..."
  curl -sL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

  echo ">>> cri-dockerd socket..."
  sudo systemctl enable cri-docker.socket
  sudo systemctl start cri-docker.socket
  sleep 2

  echo ""
  echo ">>> Готово (install). Перелогинься или: newgrp docker"
  echo ">>> Затем: make -C YandexCloud minikube-start-on-server"
  echo ">>> Grafana + VictoriaMetrics + vmagent: клонируй Riid на ВМ и из корня репо: make metrics-stack-create"
}

case "${cmd}" in
  install) do_install ;;
  minikube-start) start_minikube_none ;;
  *)
    echo "usage: $0 install | minikube-start" >&2
    exit 1
    ;;
esac
