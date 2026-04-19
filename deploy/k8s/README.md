# RIID + Dragonfly baseline manifests

This directory matches how CI runs Dragonfly: **full stack via Helm** (`scripts/values.yaml`), same as `make -C scripts _helm-install` in `.github/workflows/ci.yml`. Do **not** deploy a second `dfdaemon` in `riid-system` — the chart already runs the client DaemonSet in `dragonfly-system` and uses the hostPath socket under `/var/run/dragonfly` that RIID mounts.

- **Тома для StatefulSet (MySQL/Redis в Dragonfly):** либо **Selectech / OpenStack** — `make -C deploy/k8s/Selectech storage-default` помечает `fast.ru-3b` (Cinder) как **default** StorageClass; либо кластер **без** готового CSI — тогда `deploy/k8s/storage/local-path-storage.yaml` ([local-path-provisioner](https://github.com/rancher/local-path-provisioner)). Не смешивай два default StorageClass в одном кластере.
- **Dragonfly (all components):** Helm release `dragonfly` in namespace `dragonfly-system`.
- **RIID:** DaemonSet + ConfigMap + Service in `riid-system`.

## Prerequisites

- A Kubernetes cluster with worker nodes.
- **Helm 3** on the machine where you run install. On Ubuntu/WSL `sudo apt-get install helm` often fails (`Unable to locate package helm`); use the [official install script](https://helm.sh/docs/intro/install/) (same as CI):
  ```bash
  curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
  ```
  Ensure `helm version` works (binary is usually under `/usr/local/bin`).
- Image built from `deploy/k8s/riid/Dockerfile.k8s` and available to the cluster.
- `kubectl` context points to the target cluster.

## Apply manifests

```bash
# Selectech setup
export KUBECONFIG="$PWD/deploy/k8s/Selectech/tatum.yaml"
kubectl config get-contexts
kubectl config use-context admin@tatum
kubectl get nodes -o wide

# 0) Тома: на Selectech (Cinder) — пометить класс как default (повторяй после сброса кластера или смены default)
make -C deploy/k8s/Selectech storage-default
#    Альтернатива без OpenStack: kubectl apply -f deploy/k8s/storage/local-path-storage.yaml
kubectl get storageclass

# Если Dragonfly уже ставили без томов и PVC висят в Pending — после шага 0: helm uninstall dragonfly -n dragonfly-system (или удалить PVC) и снова ./deploy/k8s/dragonfly/install-dragonfly.sh

# 1) Full Dragonfly stack (manager, scheduler, client/dfdaemon, Redis, DB, …) — same values as CI
chmod +x deploy/k8s/dragonfly/install-dragonfly.sh
./deploy/k8s/dragonfly/install-dragonfly.sh

# 2) RIID
kubectl apply -f deploy/k8s/namespace.yaml
kubectl apply -f deploy/k8s/riid/configmap.yaml
kubectl apply -f deploy/k8s/riid/daemonset.yaml
kubectl apply -f deploy/k8s/riid/service.yaml
```

To tune Dragonfly (replicas, images), edit `scripts/values.yaml` at the repo root and re-run `install-dragonfly.sh`.

If you previously applied the old standalone `dfdaemon` in `riid-system`, remove it so only the Helm client runs:  
`kubectl -n riid-system delete daemonset dfdaemon --ignore-not-found`

## Image from CI (GitHub Actions → GHCR)

CI pushes `ghcr.io/usatovpavel/riid:<tag>` on tag builds. Update `image:` in `deploy/k8s/riid/daemonset.yaml` to match the tag you deploy.

## Smoke checks

```bash
kubectl -n dragonfly-system get pods,daemonset -o wide
kubectl -n riid-system get daemonset riid
kubectl -n riid-system get pods -l app.kubernetes.io/name=riid -o wide
kubectl -n riid-system get svc riid-metrics
```

Dragonfly client pods and RIID should be Ready; dfdaemon socket on nodes comes from the Helm-managed client, not from a duplicate manifest in `riid-system`.

### Найти под RIID на конкретной ноде

DaemonSet даёт **по одному поду на worker** — колонка **NODE** в `wide`:

```bash
kubectl -n riid-system get pods -l app.kubernetes.io/name=riid -o wide
```

Имя пода на выбранной ноде (подставь своё имя ноды из `kubectl get nodes`):

```bash
NODE=tatum-node-bm50v   # пример
RIID_POD=$(kubectl -n riid-system get pods -l app.kubernetes.io/name=riid \
  --field-selector spec.nodeName="$NODE" -o jsonpath='{.items[0].metadata.name}')
echo "$RIID_POD"
```

### Smoke: `POST /pull` через Unix-сокет (и Dragonfly, если включён в `configmap`)

В slim-образе нет `curl`. **Вручную в уже запущенном поде** (одноразово, до пересоздания пода):

```bash
kubectl -n riid-system exec -it "$RIID_POD" -- bash -c \
  'apt-get update && apt-get install -y --no-install-recommends curl'
```

Дальше:

```bash
kubectl -n riid-system exec -it "$RIID_POD" -- \
  curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
  -H 'Content-Type: application/json' \
  -d '{"repository":"library/busybox","reference":"latest","runtimeId":"podman"}'
```