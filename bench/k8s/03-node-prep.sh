#!/usr/bin/env bash
# Kernel prerequisites, containerd CRI, kube tooling. Idempotent.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

prep_one() {
  local alias="$1" ip
  ip="$(node_run "$alias" "ip -4 -o addr show ${STAND_CLUSTER_NIC} | awk '{print \$4}' | cut -d/ -f1")"
  say "$alias: prepping (node-ip ${ip})"

  # VirtualBox NAT has no IPv6 route, but DNS answers AAAA first: without this
  # every apt/curl stalls ~77s before falling back.
  node_sudo "$alias" "sh -c 'echo Acquire::ForceIPv4 \\\"true\\\"\; > /etc/apt/apt.conf.d/99force-ipv4'"

  node_sudo "$alias" "swapoff -a"
  node_sudo "$alias" "sed -i.bak '/\\sswap\\s/s/^/#/' /etc/fstab"
  node_sudo "$alias" "sh -c 'printf \"overlay\\nbr_netfilter\\n\" > /etc/modules-load.d/k8s.conf'"
  node_sudo "$alias" "modprobe overlay"
  node_sudo "$alias" "modprobe br_netfilter"
  node_sudo "$alias" "sh -c 'printf \"net.bridge.bridge-nf-call-iptables=1\\nnet.bridge.bridge-nf-call-ip6tables=1\\nnet.ipv4.ip_forward=1\\n\" > /etc/sysctl.d/k8s.conf'"
  node_sudo "$alias" "sysctl --system >/dev/null"

  node_sudo "$alias" "mkdir -p /etc/containerd"
  node_sudo "$alias" "sh -c 'containerd config default > /etc/containerd/config.toml'"
  node_sudo "$alias" "sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml"
  node_sudo "$alias" "sed -i 's#sandbox_image = .*#sandbox_image = \\\"registry.k8s.io/pause:3.10\\\"#' /etc/containerd/config.toml"
  node_sudo "$alias" "sed -i 's#sandbox = .*#sandbox = \\\"registry.k8s.io/pause:3.10\\\"#' /etc/containerd/config.toml"
  node_sudo "$alias" "systemctl restart containerd"
  node_sudo "$alias" "systemctl enable containerd >/dev/null 2>&1"

  if ! node_run "$alias" "command -v kubeadm >/dev/null"; then
    node_sudo "$alias" "apt-get update -qq"
    node_sudo "$alias" "apt-get install -y -qq apt-transport-https ca-certificates curl gpg >/dev/null"
    node_sudo "$alias" "mkdir -p /etc/apt/keyrings"
    node_run "$alias" "curl -4 -fsSL --retry 5 --retry-delay 3 -m 120 'https://pkgs.k8s.io/core:/stable:/${K8S_MINOR}/deb/Release.key' -o /tmp/k8s.key"
    node_sudo "$alias" "gpg --batch --yes --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg /tmp/k8s.key"
    node_sudo "$alias" "sh -c \"echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/${K8S_MINOR}/deb/ /' > /etc/apt/sources.list.d/kubernetes.list\""
    node_sudo "$alias" "apt-get update -qq"
    node_sudo "$alias" "apt-get install -y -qq kubelet kubeadm kubectl"
    node_sudo "$alias" "apt-mark hold kubelet kubeadm kubectl >/dev/null"
  fi

  # A minikube install on the base image overwrote kubeadm's kubelet dropin with
  # one pinning minikube's own binary and the pre-clone hostname. The symptom is
  # a healthy control plane whose Node object never appears.
  if node_run "$alias" "test -f /usr/lib/systemd/system/kubelet.service.d/10-kubeadm.conf"; then
    node_sudo "$alias" "rm -f /etc/systemd/system/kubelet.service.d/10-kubeadm.conf"
  fi
  node_sudo "$alias" "rm -rf /var/lib/minikube"
  node_sudo "$alias" "sh -c \"printf 'KUBELET_EXTRA_ARGS=--node-ip=${ip}\\n' > /etc/default/kubelet\""
  node_sudo "$alias" "systemctl daemon-reload"
  node_sudo "$alias" "systemctl enable kubelet >/dev/null 2>&1"
  say "$alias: kubeadm $(node_run "$alias" 'kubeadm version -o short 2>/dev/null')"
}

for alias in $STAND_SSH; do prep_one "$alias" & done
wait
say "all nodes prepped"
