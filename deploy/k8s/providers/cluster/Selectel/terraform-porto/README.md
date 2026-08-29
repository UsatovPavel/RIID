# Terraform: Ubuntu 22.04 stand for the Porto arm

Creates the cluster the Porto benchmark runs on, and nothing that lives inside
it. Dragonfly, RIID, the registry and the datasets stay with `bootstrap/` and
helm, exactly as for the MKS stand next door.

## Why a second module

Porto is packaged for focal and jammy only: upstream's own build image is
`FROM ubuntu:22.04`, `debian/control.in` build-depends on `libncurses5-dev` and
`python-all` (neither exists in noble), and the releases of `ten-nancy/porto`
carry `porto_focal_*.deb` and `porto_jammy_*.deb` and nothing newer.

Selectel MKS gives no way to pick the node OS — neither `selectel_mks_cluster_v1`
nor `selectel_mks_nodegroup_v1` has an image attribute — and the oldest
Kubernetes version it offers still lands on Ubuntu 24.04.4. So the Porto stand is
built from plain cloud servers with kubeadm instead, on the same flavor, the same
disk tier and the same zone as the MKS stand, so the two stay comparable.

| | MKS (`../terraform`) | this module |
|---|---|---|
| provider | `selectel` | `openstack`, same Keystone and service user |
| node OS | Ubuntu 24.04, not selectable | `Ubuntu 22.04 LTS 64-bit` |
| control plane | managed by Selectel | own server, `kubeadm init` |
| kubeconfig | data source | pulled over ssh by `make kubeconfig` |
| written to | `../serverConfig.yaml` | `../serverConfig-porto.yaml` |

Both stands can exist at once, which is why the kubeconfig paths differ: point
`bootstrap/` at one explicitly with `CONFIG_FILE=`.

## Usage

```bash
make stand-6              # init + plan + apply + kubeconfig + wait + smoke
make stand-12             # the same at the production size
make nodes                # kubectl get nodes through the written kubeconfig
make smoke                # portoctl --version on every worker
make destroy
```

`NODES` defaults to `cluster_topology` in `config/config.yaml` (workers +
observers + registry_nodes), so `make stand` alone builds the configured size.

Then hand the stand to the bootstrap chain:

```bash
make -C ../../../../bootstrap install-all \
  CONFIG_FILE=../providers/cluster/Selectel/serverConfig-porto.yaml \
  STORAGE_CLASS=local-path
```

`STORAGE_CLASS` matters: `fast.ru-3b` is a Cinder class the MKS CSI creates, and
a self-managed cluster has no CSI. `src/storage/local-path-storage.yaml` is what
provides a class here.

## What cloud-init does

Both roles share `cloud-init/common-node.sh.tftpl`: swap off, the two kernel
modules and sysctls kubeadm needs, `containerd.io` from the Docker repository
(jammy's own containerd is 1.6 and does not understand a version 3 config), then
`kubelet`/`kubeadm`/`kubectl` from `pkgs.k8s.io`, all pinned with `apt-mark hold`.

The control plane then runs `kubeadm init` and applies flannel, patched to the
`pod_cidr` of the stand. Each worker installs the Porto release deb, waits for
port 6443 on the control plane and runs `kubeadm join`.

The join address is fixed before anything exists (`cidrhost(subnet_cidr, 10)`,
pinned on a Neutron port), so no worker has to look up where to join.

## Credentials

Identical to the MKS module: `tf.sh` exports them from `deploy/k8s/config/.env`,
and the file is parsed by `load-env.inc.sh` rather than sourced — `set -a; . .env`
makes bash expand `$`, backticks and backslashes inside the values and silently
truncates any password containing them.

## Access

Terraform generates the keypair; `make ssh-key` writes the private half to
`.ssh/id_ed25519` (gitignored), and `make ssh` opens a shell on the control
plane. Workers have no floating ip and are reached through it.

`ssh_allowed_cidr` defaults to `0.0.0.0/0` for ssh and 6443. Narrow it to the
workstation address when the stand lives longer than a run: the bootstrap token
is valid for `kubeadm_token_ttl` (3h by default) and the workers join with
`unsafeSkipCAVerification`.

## State

State stays local and is gitignored; it holds the ssh private key and the
bootstrap token. Do not commit it, and keep `terraform.tfstate` on the
workstation that owns the stand.
