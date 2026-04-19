# RIID + Dragonfly baseline manifests

This directory contains a minimal Kubernetes baseline for PR 17:
- `dfdaemon` as a DaemonSet on worker nodes.
- RIID daemon deployment using `deploy/k8s/riid/Dockerfile.k8s` image.
- Service for RIID metrics (`/metrics` on port `9090`).

## Prerequisites

- A Kubernetes cluster with worker nodes.
- Image built from `deploy/k8s/riid/Dockerfile.k8s` and available to the cluster.
- `kubectl` context points to the target cluster.

## Apply manifests

```bash
kubectl apply -f deploy/k8s/namespace.yaml
kubectl apply -f deploy/k8s/dragonfly-dfdaemon-daemonset.yaml
kubectl apply -f deploy/k8s/riid/configmap.yaml
kubectl apply -f deploy/k8s/riid/deployment.yaml
kubectl apply -f deploy/k8s/riid/service.yaml
```

## Build and push RIID image (example)

```bash
docker build -f deploy/k8s/riid/Dockerfile.k8s -t <registry>/riid:k8s .
docker push <registry>/riid:k8s
```

Then update `image:` in `deploy/k8s/riid/deployment.yaml`.

## Smoke checks

```bash
kubectl -n riid-system get daemonset dfdaemon
kubectl -n riid-system get pods -l app.kubernetes.io/name=dfdaemon -o wide
kubectl -n riid-system get deploy riid
kubectl -n riid-system get svc riid-metrics
```

`dfdaemon` should be scheduled on worker nodes and be Ready.
