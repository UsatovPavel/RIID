# Terraform: MKS stand for the RIID benchmark

Creates the cluster the benchmark runs on, and nothing that lives inside it.
Dragonfly, RIID, the registry and the datasets stay with `bootstrap/` and helm —
Terraform only owns what exists before the Kubernetes API is reachable.

Shape comes from AGENT-98: region `ru-3`, cluster type `BASIC` (one master),
nodes with 4 vCPU / 8 GB / 140 GB on the `fast` disk tier (25000 IOPS, 500 MB/s), zone `ru-3b`.
Node count is read from `config/config.yaml` (`cluster_topology`), so the
preparation stand is 6 nodes and the production one is 12 without editing HCL.

## Credentials

`tf.sh` exports them from `deploy/k8s/config/.env`, so no secret reaches a
tfvars file or the shell history. The file is parsed by `load-env.inc.sh` rather
than sourced: `set -a; . .env` makes bash expand `$`, backticks and backslashes
inside the values, which silently truncates any password containing them and
turns a valid account into a confusing `Authentication failed`.

| variable               | meaning                                          |
|------------------------|--------------------------------------------------|
| `SELECTEL_ACCOUNT_ID`  | account number, used as the Keystone domain name |
| `SELECTEL_IAM_USER`    | service user with access to the project          |
| `SELECTEL_IAM_PASSWORD`| its password                                     |
| `SELECTEL_IAM_PROJECT` | project name                                     |
| `SELECTEL_PROJECT_ID`  | project id; optional, resolved from the name     |
| `SELECTEL_API_TOKEN`   | panel static token, only used to resolve that id |

The service user needs a role in the project — without one Keystone answers 401
and the provider fails with `failed to create auth provider: Authentication
failed`. A panel static token cannot stand in for it: by design it gives no
access to OpenStack objects, so it reads the project list but cannot create a
cluster.

## Usage

```bash
make init
make plan                 # NODES defaults to cluster_topology from config.yaml
make plan NODES=6         # preparation stand
make apply
make kubeconfig           # writes ../serverConfig.yaml, what bootstrap/ reads
make -C ../../../../bootstrap install-all
make destroy
```

`tf.sh` resolves the project id on its own; `resolve-project-id.sh` can also be run
directly if you need just the id.

## State

State stays local and is gitignored; it holds the kubeconfig and therefore the
cluster client certificate. Do not commit it, and keep `terraform.tfstate` on the
workstation that owns the stand.
