#!/usr/bin/env bash
# kubeadm init on the first node, Calico, then join the rest.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

CP_ALIAS="$(stand_field "$STAND_SSH" 1)"
CP_NODE="$(stand_field "$STAND_NODES" 1)"
CP_IP="$(stand_field "$STAND_IPS" 1)"

say "resetting any previous cluster state on all nodes"
for alias in $STAND_SSH; do
  node_sudo "$alias" "kubeadm reset -f --cri-socket=${CRI_SOCKET} >/dev/null 2>&1"
  node_sudo "$alias" "rm -rf /etc/kubernetes /var/lib/etcd /root/.kube"
done

say "kubeadm init on ${CP_NODE} (${CP_IP})"
node_sudo "$CP_ALIAS" "kubeadm config images pull --cri-socket=${CRI_SOCKET} --kubernetes-version=${K8S_VERSION} >/dev/null"
node_sudo "$CP_ALIAS" "kubeadm init --cri-socket=${CRI_SOCKET} --apiserver-advertise-address=${CP_IP} --pod-network-cidr=${POD_CIDR} --node-name=${CP_NODE} --kubernetes-version=${K8S_VERSION}" \
  | tail -5

node_run "$CP_ALIAS" "mkdir -p ~/.kube"
node_sudo "$CP_ALIAS" "cp -f /etc/kubernetes/admin.conf /home/${STAND_USER}/.kube/config"
node_sudo "$CP_ALIAS" "chown ${STAND_USER}:${STAND_USER} /home/${STAND_USER}/.kube/config"

mkdir -p "$(dirname "$KUBECONFIG_OUT")"
scp -q "${CP_ALIAS}:~/.kube/config" "$KUBECONFIG_OUT"
sed -i "s#server: https://.*:6443#server: https://${CP_IP}:6443#" "$KUBECONFIG_OUT"
chmod 600 "$KUBECONFIG_OUT"
say "kubeconfig written to ${KUBECONFIG_OUT}"

kube get nodes >/dev/null 2>&1 || die "kubeconfig does not reach the API server"

say "installing Calico ${CALICO_VERSION}"
calico="$(mktemp)"
curl -4 -fsSL --retry 5 -m 180 \
  "https://raw.githubusercontent.com/projectcalico/calico/${CALICO_VERSION}/manifests/calico.yaml" -o "$calico" \
  || die "could not download the Calico manifest"
kube apply -f "$calico" >/dev/null
rm -f "$calico"

# Two settings that are not optional on this stand:
#  - the IPv4 pool must match --pod-network-cidr, and must not be Calico's
#    192.168.0.0/16 default, which would swallow the host-only network;
#  - autodetection must be pinned to the cluster CIDR. Left to itself it picks
#    the default route, i.e. the NAT adapter, which is the SAME address on every
#    clone: Calico then reports an address conflict and the workers crashloop.
kube -n kube-system set env daemonset/calico-node \
  CALICO_IPV4POOL_CIDR="$POD_CIDR" \
  IP_AUTODETECTION_METHOD="cidr=${STAND_HOSTONLY_NET}" >/dev/null
kube -n kube-system rollout status daemonset/calico-node --timeout=10m | tail -1

JOIN="$(node_sudo "$CP_ALIAS" "kubeadm token create --print-join-command" | tail -1)"
[ -n "$JOIN" ] || die "could not create a join command"

i=0
for alias in $STAND_SSH; do
  i=$((i+1))
  [ "$i" = 1 ] && continue
  node="$(stand_field "$STAND_NODES" "$i")"
  say "joining ${node}"
  node_sudo "$alias" "${JOIN} --cri-socket=${CRI_SOCKET} --node-name=${node}" | tail -2
done

wait_nodes_ready "$(stand_count)" || die "not all nodes became Ready"

i=0
for node in $STAND_NODES; do
  i=$((i+1))
  [ "$i" = 1 ] && continue
  kube label node "$node" node-role.kubernetes.io/worker= --overwrite >/dev/null
done
# The first node keeps its control-plane taint on purpose: it hosts the local
# registry, it is not a bench worker, and letting arms schedule there would put
# registry I/O on the same disk being measured.
kube get nodes -o wide
