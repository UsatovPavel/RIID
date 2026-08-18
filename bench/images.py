"""Образы для handoff-бенчмарка (AGENT-71/AGENT-72). Список зафиксирован вручную.

Происхождение: `make -C deploy/k8s/providers generate-registry-image-lists`
(→ registry/image/dataset/output/dataset_{provider}_a.tsv, колонки
repository/tag/size_bytes/size_human). Генератор джойнит два входа:
deploy/k8s/config/imagelist/dockerhub.yaml (.test.images) и
deploy/k8s/config/imagelist/presented_images_list_sizes.tsv — из них 10 образов
и выписаны, 2026-08-18.

Критерий отбора (эпик: "Select 10 images from 10 to 50 mb"):
  1. размер 10..50 MB;
  2. нет общей базы с python:latest.
Пункт 2 — почему из диапазона исключены library/debian:trixie-backports,
library/httpd, library/memcached, bitnami/redis: они debian-based, их слои podman
распакует на шаге "download 10 images", и на замеряемом шаге podman пропустит их
как уже присутствующие, занизив handoff. Это ровно та ловушка, из-за которой в
кластерном трейсе (Optimization.md §1) 3 слоя python пришли как cache hit.

Имена даны в канонической dockerhub-нотации; в имена конкретного реестра их
переводит bench.registry.map_repository() по правилам
deploy/k8s/providers/registry/image/mapper-common.sh.
"""

# (repository, tag, size_bytes)
WARMUP_IMAGES: tuple[tuple[str, str, int], ...] = (
    ("curlimages/curl", "latest", 10628359),
    ("cfcommunity/slack-notification-resource", "latest", 10817066),
    ("prom/node-exporter", "latest", 13302026),
    ("stakater/reloader", "merge-1138-ubi", 19326980),
    ("library/registry", "latest", 20187413),
    ("istio/operator", "1.23.6-distroless", 29505825),
    ("bitnami/sealed-secrets-controller", "latest", 29652946),
    ("library/ubuntu", "latest", 29732978),
    ("google/cadvisor", "latest", 30525033),
    ("presearch/node", "latest", 39972522),
)

# 11-й образ, единственный измеряемый.
TARGET_IMAGE: tuple[str, str, int] = ("library/python", "latest", 414365378)
