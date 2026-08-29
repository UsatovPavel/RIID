# ADR-12: The Porto stand is a self-managed kubeadm cluster, not MKS

## Context

Porto is packaged for focal and jammy only: upstream builds in `FROM ubuntu:22.04`,
`debian/control.in` build-depends on `libncurses5-dev` and `python-all` (gone in
noble), and `ten-nancy/porto` releases ship focal and jammy debs and nothing newer.
Selectel MKS exposes no image attribute on cluster or node group, and even its oldest
Kubernetes version comes up on Ubuntu 24.04.4 — verified on a real cluster.

## Decision

A second Terraform module, `providers/cluster/Selectel/terraform-porto/`, builds the
stand from plain cloud servers through the OpenStack provider against the same
Keystone and service user: private network with a router for SNAT, one control plane
on a pinned address, `NODES` workers, all from `Ubuntu 22.04 LTS 64-bit`, on the same
flavor, disk tier and zone as MKS. cloud-init installs containerd and kubeadm
everywhere, `kubeadm init` plus flannel on the control plane, `kubeadm join` on the
workers, and Porto from the release deb — the package installs into `/usr/sbin` and
enables `porto.service` itself, while a source build would pull the whole
build-depends onto every node. The kubeconfig goes to `serverConfig-porto.yaml`, so
both stands can exist at once.

## Consequences

- The stands stay comparable in hardware and differ only in OS and control plane.
- We own the control plane now: no managed repair, no CSI. `bootstrap/` needs
  `STORAGE_CLASS=local-path` here, since `fast.ru-3b` is an MKS Cinder class.
- The bootstrap token and the ssh key live in local state; the token expires in 3h
  and `ssh_allowed_cidr` should be narrowed for a long-lived stand.
- Porto follows the newest release (5.3.41), older than the vendored 5.3.58 sources.
