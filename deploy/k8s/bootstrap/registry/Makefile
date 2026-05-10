REGISTRY_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
SELECTEL_DIR ?= $(abspath $(REGISTRY_DIR)/..)
K8S ?= $(abspath $(SELECTEL_DIR)/..)
CONFIG_FILE ?= ../providers/cluster/Selectel/serverConfig.yaml
KUBECONFIG ?= $(abspath $(SELECTEL_DIR)/$(CONFIG_FILE))

LOCAL_REGISTRY_MANIFEST ?= $(K8S)/.resolved/performance/registry/local-registry.yaml
PROVIDER_APPLY_SH ?= bash "$(K8S)/providers/registry/image/provider-apply.sh" --provider dockerhub --root "$(K8S)"
PERF_REGISTRY_DATASET ?= $(K8S)/performance/input/dataset_a.tsv
INIT_WORKER_IMAGE_SCRIPT ?= $(REGISTRY_DIR)/init/init-worker-image.sh
ENV_FILE ?= $(K8S)/config/.env
# Не перегенерировать dataset под все профили: SKIP_REGISTRY_PROVIDER_PERF_SYNC=1
PROVIDERS_MAKE_DIR := $(K8S)/providers

DRAGONFLY_REGISTRY_DIR := $(K8S)/src/dragonfly/registry
DRAGONFLY_PULL_SECRET_SCRIPT := $(DRAGONFLY_REGISTRY_DIR)/setup-dockerhub-pull-secret.sh
DRAGONFLY_SELECTEL_PULL_SECRET_SCRIPT := $(DRAGONFLY_REGISTRY_DIR)/setup-selectel-registry-pull-secret.sh

.PHONY: secret-config-riid registry-dockerhub registry-selectel registry-local registry-apply-profile \
	install-local-registry wait-local-registry init-worker-tooling-image load-performance-registry-dataset \
	init-performance-registry-images init-performance-registry-images-state-init init-performance-registry-images-incremental \
	dragonfly-dockerhub-pull-secret dragonfly-selectel-registry-pull-secret

dragonfly-dockerhub-pull-secret:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	bash "$(DRAGONFLY_PULL_SECRET_SCRIPT)"

dragonfly-selectel-registry-pull-secret:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	bash "$(DRAGONFLY_SELECTEL_PULL_SECRET_SCRIPT)" "$(K8S)/config/.env"

secret-config-riid:
	@$(MAKE) -C "$(REGISTRY_DIR)" registry-apply-profile PROFILE=dockerhub K8S="$(K8S)" KUBECONFIG="$(KUBECONFIG)" SELECTEL_DIR="$(SELECTEL_DIR)"

registry-dockerhub:
	@$(MAKE) -C "$(REGISTRY_DIR)" registry-apply-profile PROFILE=dockerhub K8S="$(K8S)" KUBECONFIG="$(KUBECONFIG)" SELECTEL_DIR="$(SELECTEL_DIR)"

registry-selectel:
	@$(MAKE) -C "$(REGISTRY_DIR)" registry-apply-profile PROFILE=selectel K8S="$(K8S)" KUBECONFIG="$(KUBECONFIG)" SELECTEL_DIR="$(SELECTEL_DIR)"

registry-local:
	@$(MAKE) -C "$(REGISTRY_DIR)" registry-apply-profile PROFILE=local K8S="$(K8S)" KUBECONFIG="$(KUBECONFIG)" SELECTEL_DIR="$(SELECTEL_DIR)"

# Pull-secret → RIID Secret/rollout → [selectel: imagelist-overlays + Helm fragment] → install-dragonfly.sh → perf lists (опционально).
registry-apply-profile:
	@if [ -z "$(PROFILE)" ]; then echo "registry-apply-profile: set PROFILE=dockerhub|selectel|local"; exit 1; fi; \
	case "$(PROFILE)" in dockerhub|selectel|local) ;; *) echo "registry-apply-profile: invalid PROFILE=$(PROFILE)"; exit 1;; esac; \
	export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	[ -f "$(K8S)/config/.env" ] || { echo "registry-apply-profile: нет $(K8S)/config/.env (см. config/.env.example)"; exit 1; }; \
	command -v helm >/dev/null || { echo "registry-apply-profile: helm not found"; exit 1; }; \
	command -v yq >/dev/null || { echo "registry-apply-profile: yq not found"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	if [ "$(PROFILE)" = dockerhub ]; then \
	  $(MAKE) dragonfly-dockerhub-pull-secret K8S="$(K8S)" KUBECONFIG="$(KUBECONFIG)" SELECTEL_DIR="$(SELECTEL_DIR)"; \
	else \
	  $(MAKE) dragonfly-selectel-registry-pull-secret K8S="$(K8S)" KUBECONFIG="$(KUBECONFIG)" SELECTEL_DIR="$(SELECTEL_DIR)"; \
	fi; \
	bash "$(K8S)/src/riid/render-riid-config-secret.sh" --apply --profile "$(PROFILE)"; \
	if kubectl -n riid-system get daemonset riid >/dev/null 2>&1; then \
	  kubectl -n riid-system rollout restart daemonset riid; \
	  kubectl -n riid-system rollout status daemonset riid; \
	else \
	  echo "registry-apply-profile: daemonset/riid not found yet, restart skipped"; \
	fi; \
	if [ "$(PROFILE)" = selectel ]; then \
	  bash "$(K8S)/src/riid/registry/sync-podman-selectel-login.sh"; \
	fi; \
	if [ "$(PROFILE)" = selectel ]; then \
	  echo "registry-apply-profile: imagelist-overlays (Selectel Helm fragment -> .resolved)"; \
	  $(MAKE) -C "$(PROVIDERS_MAKE_DIR)" imagelist-overlays CONFIG_ENV="$(K8S)/config/.env"; \
	fi; \
	echo "registry-apply-profile: helm upgrade dragonfly (render-values + .resolved Helm fragment)"; \
	DRAGONFLY_REGISTRY_PROFILE="$(PROFILE)" bash "$(K8S)/src/dragonfly/install-dragonfly.sh"; \
	if [ -z "$${SKIP_REGISTRY_PROVIDER_PERF_SYNC:-}" ]; then \
	  echo "registry-apply-profile: generate-registry-image-lists (providers)"; \
	  $(MAKE) -C "$(PROVIDERS_MAKE_DIR)" generate-registry-image-lists CONFIG_ENV="$(K8S)/config/.env"; \
	else \
	  echo "registry-apply-profile: skip perf lists (SKIP_REGISTRY_PROVIDER_PERF_SYNC set)" >&2; \
	fi; \
	PERF_IN="$(K8S)/performance/input"; \
	DSA="$(K8S)/providers/registry/image/dataset/output/dataset_$(PROFILE)_a.tsv"; \
	DSB="$(K8S)/providers/registry/image/dataset/output/dataset_$(PROFILE)_b.tsv"; \
	mkdir -p "$$PERF_IN"; \
	if [ -f "$$DSA" ]; then cp "$$DSA" "$$PERF_IN/dataset_a.tsv"; echo "registry-apply-profile: performance/input/dataset_a.tsv <- $$DSA"; else echo "registry-apply-profile: warning: missing $$DSA" >&2; fi; \
	if [ -f "$$DSB" ]; then cp "$$DSB" "$$PERF_IN/dataset_b.tsv"; echo "registry-apply-profile: performance/input/dataset_b.tsv <- $$DSB"; else echo "registry-apply-profile: warning: missing $$DSB" >&2; fi; \
	GLOBAL_CFG="$(K8S)/config/config.yaml"; \
	RP_LABEL=""; \
	case "$(PROFILE)" in \
	  dockerhub) RP_LABEL='Docker Hub';; \
	  selectel) RP_LABEL='Selectel';; \
	  local) RP_LABEL='local';; \
	esac; \
	yq e -i '.registry_provider = $$rp' --arg rp "$$RP_LABEL" "$$GLOBAL_CFG"; \
	yq e -i '.test_registry_provider = $$tp' --arg tp "$(PROFILE)" "$$GLOBAL_CFG"; \
	echo "registry-apply-profile: $$GLOBAL_CFG registry_provider=$$RP_LABEL test_registry_provider=$(PROFILE)"

install-local-registry:
	@$(PROVIDER_APPLY_SH)
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	kubectl apply -f "$(LOCAL_REGISTRY_MANIFEST)"

wait-local-registry:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	kubectl -n registry-system rollout status deployment/local-registry

init-worker-tooling-image:
	@"$(INIT_WORKER_IMAGE_SCRIPT)" "$(K8S)/config/.env"

load-performance-registry-dataset:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	LOADER_IMG="$${REGISTRY_LOADER_IMAGE:-}"; \
	if [ -z "$$LOADER_IMG" ]; then \
	  _ENV_FILE="$(K8S)/config/.env"; \
	  [ -f "$$_ENV_FILE" ] || { echo "load-performance-registry-dataset: set REGISTRY_LOADER_IMAGE or create $$_ENV_FILE (copy deploy/k8s/config/.env.example)"; exit 1; }; \
	  LOADER_IMG=$$(set -euo pipefail; set -a; . "$$_ENV_FILE"; set +a; \
	    . "$(REGISTRY_DIR)/registry-prefix-from-env.inc.sh"; \
	    riid_registry_prefix_from_env; \
	    printf '%s/worker/k8s-worker-tooling:24.04' "$$REG_PREFIX"); \
	fi; \
	echo "load-performance-registry-dataset: REGISTRY_LOADER_IMAGE=$$LOADER_IMG" >&2; \
	DATASET_FILE="$(PERF_REGISTRY_DATASET)" REGISTRY_LOADER_IMAGE="$$LOADER_IMG" \
	  bash "$(K8S)/performance/registry/load-dataset-into-local-registry.sh"

init-performance-registry-images:
	@"$(REGISTRY_DIR)/init/init-performance-registry-images.sh" "$(ENV_FILE)"

init-performance-registry-images-state-init:
	@"$(REGISTRY_DIR)/init/init-performance-registry-images-incremental.sh" --init-state "$(ENV_FILE)"

init-performance-registry-images-incremental:
	@"$(REGISTRY_DIR)/init/init-performance-registry-images-incremental.sh" $(REGISTRY_INCREMENTAL_ARGS) "$(ENV_FILE)"
