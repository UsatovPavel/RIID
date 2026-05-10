# Registry-facing image lists (imagelist overlays + performance datasets из catalog YAML).
# Requires deploy/k8s/config/.env (see deploy/k8s/config/.env.example).

PROVIDERS_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
K8S_ROOT := $(abspath $(PROVIDERS_DIR)/..)
CONFIG_ENV ?= $(K8S_ROOT)/config/.env

IMAGE_DIR := $(PROVIDERS_DIR)/registry/image
DATASET_DIR := $(IMAGE_DIR)/dataset

.PHONY: generate-registry-image-lists provider-apply imagelist-overlays

# Собрать deploy/k8s/.resolved/ (подстановка image из imagelist/dockerhub.yaml .infra.images); исходники в src/ не меняются.
provider-apply:
	bash "$(IMAGE_DIR)/provider-apply.sh" --provider dockerhub --root "$(K8S_ROOT)"

# YAML overlays selectel/local из dockerhub.yaml (mapper-common + imagelist_emit_overlays.py).
imagelist-overlays:
	@command -v yq >/dev/null || { echo "yq not found"; exit 1; }
	PROVIDER_REGISTRY_ENV_FILE="$(CONFIG_ENV)" bash "$(IMAGE_DIR)/imagelist-build-overlays.sh"

CONFIG_YAML := $(K8S_ROOT)/config/config.yaml

generate-registry-image-lists:
	@test -f "$(CONFIG_ENV)" || { echo "$(CONFIG_ENV) missing — copy deploy/k8s/config/.env.example"; exit 1; }
	@test -f "$(CONFIG_YAML)" || { echo "$(CONFIG_YAML) missing"; exit 1; }
	@command -v python3 >/dev/null || { echo "python3 not found"; exit 1; }
	@$(MAKE) -C "$(PROVIDERS_DIR)" imagelist-overlays CONFIG_ENV="$(CONFIG_ENV)"
	python3 "$(DATASET_DIR)/emit_datasets_from_imagelist.py" --k8s-root "$(K8S_ROOT)"
	@echo "generate-registry-image-lists: done"
