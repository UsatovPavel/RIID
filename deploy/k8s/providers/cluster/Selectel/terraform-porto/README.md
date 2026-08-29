# Terraform: Ubuntu 22.04 stand for the Porto arm

Creates the cluster the Porto benchmark runs on, and nothing that lives inside
it. Dragonfly, RIID, the registry and the datasets stay with `bootstrap/` and
helm, exactly as for the MKS stand next door.

## How it differs from the MKS stand

Why this stand exists at all — Porto's packaging and what MKS can and cannot
offer — is in `docs/design records/PR 20 Porto stand/ADR-12-porto-stand-outside-mks.md`.
Flavor, disk tier and zone are the same as MKS so the two stay comparable; the
rest differs as follows.

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
make bootstrap            # bootstrap install-all against this stand
```

That target passes this stand's kubeconfig and `STORAGE_CLASS=local-path`.
The class matters: `fast.ru-3b` is a Cinder class the MKS CSI creates, and a
self-managed cluster has no CSI, so `bootstrap`'s `storage-default` step installs
`src/storage/local-path-storage.yaml` when the requested class is missing.

## What cloud-init does

Both roles share `cloud-init/common-node.sh.tftpl`: swap off, the two kernel
modules and sysctls kubeadm needs, `containerd.io` from the Docker repository
(jammy's own containerd is 1.6 and does not understand a version 3 config), then
`kubelet`/`kubeadm`/`kubectl` from `pkgs.k8s.io`, all pinned with `apt-mark hold`.

The control plane then runs `kubeadm init` and applies flannel, patched to the
`pod_cidr` of the stand. Each worker installs the Porto release deb, waits for
port 6443 on the control plane and runs `kubeadm join`.

Workers also get `/etc/portod.conf.d/10-riid-bench.conf` with
`docker_images_support: true`, without which `portoctl docker-pull` — the whole
basis of the Porto bench arm — is unavailable. An HTTP registry has to be listed
in `porto_insecure_registries`: Porto has no per-command equivalent of podman's
`--tls-verify=false` or ctr's `--plain-http`. Note that portod resolves names in
the host netns, where cluster DNS does not exist, so a `*.svc.cluster.local`
address will not work for that arm.

The join address is fixed before anything exists (`cidrhost(subnet_cidr, 10)`,
pinned on a Neutron port), so no worker has to look up where to join.

## Credentials

Identical to the MKS module, and literally the same code: the shell layer
(`tf.sh`, `load-env.inc.sh`, `resolve-project-id.sh`) lives in
`../stand-common/` and is shared by both stands. It exports the credentials from
`deploy/k8s/config/.env`, parsing the file with `load-env.inc.sh` rather than
sourcing it — `set -a; . .env`
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
