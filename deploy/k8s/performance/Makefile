CONFIG_FILE ?= ../providers/cluster/Selectel/serverConfig.yaml
KUBECONFIG := $(abspath $(CONFIG_FILE))
PERF_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
K8S := $(abspath $(PERF_DIR)/..)
GLOBAL_CONFIG_FILE := $(K8S)/config/config.yaml
REGISTRY_DIR := $(PERF_DIR)/registry
REGISTRY_TC_SCRIPT := $(REGISTRY_DIR)/registry-node-tc.sh
REGISTRY_TC_CHECK_SCRIPT := $(REGISTRY_DIR)/registry-node-tc-check.sh
INPUT_DIR := $(PERF_DIR)/input

# Сценарий один — recreate, поэтому MODE/CONCURRENCY больше нет.
# BACKEND — откуда тянется образ, ENGINE — куда он приезжает.
BACKEND ?= riid
ENGINE ?= podman
# EXPECTED_RIID_PODS — опционально; если пусто, run-pull-scenario.sh берёт cluster_topology.workers из deploy/k8s/config/config.yaml (0 = не проверять число подов).

# export, а не строка в env-префиксе run: значение нужно только скрипту, и
# переопределение из командной строки (make run SCENARIO=...) всё равно экспортируется.
export SCENARIO ?= pr18
DATASET ?= A
DATASET_FILE ?=
# Для BACKEND=riid движок выбирается через runtimeId в запросе к демону.
RUNTIME_ID ?= $(ENGINE)
OUTPUT_TSV ?=
PODMAN_CLEAR_CACHE_BEFORE_PULL ?= 0
PODMAN_REGISTRY_PULL_HOST=local-registry.registry-system.svc.cluster.local:5000
# for local registry. if need selectel registry set empty 

OUTPUT_DIR ?= $(PERF_DIR)/output
SUMMARIZE_SCRIPT := $(PERF_DIR)/summarize/summarize-riid-vs-podman.sh
AGGREGATE_SCRIPT := $(PERF_DIR)/summarize/cluster-aggregate-time.sh
SUMMARY_OUT := $(OUTPUT_DIR)/summary.tsv
# Какую пару армов сводить: любые два TSV из OUTPUT_DIR.
SUMMARY_A ?= $(OUTPUT_DIR)/riid-podman.tsv
SUMMARY_B ?= $(OUTPUT_DIR)/bare-podman.tsv

ifeq ($(DATASET_FILE),)
  ifeq ($(DATASET),A)
    DATASET_FILE := $(INPUT_DIR)/dataset_a.tsv
  else ifeq ($(DATASET),B)
    DATASET_FILE := $(INPUT_DIR)/dataset_b.tsv
  endif
endif

.PHONY: run riid-podman riid-containerd bare-podman bare-containerd dfinit-podman dfinit-containerd \
        clear-cluster-cache delete-local-registry-dataset debug-registry-node \
        registry-node-tc-apply registry-node-tc-clear registry-node-tc-check summarize summarize-aggregate

CLEAR_CLUSTER_SCRIPT := $(PERF_DIR)/scenario/clear/clear-cache-all-riid-pods.sh
DELETE_REGISTRY_SCRIPT := $(PERF_DIR)/registry/delete-dataset-from-local-registry.sh
# Удаление тегов из local-registry по TSV (см. delete-dataset-from-local-registry.sh).
# DATASET / DATASET_FILE — как у цели run.
# REGISTRY_URL — если пусто, делается port-forward svc/local-registry → 127.0.0.1:5000 на время скрипта.
REGISTRY_URL ?=
DELETE_DATASET_RUN_GC ?= 0
DELETE_DATASET_DRY_RUN ?= 0

run:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	out="$(OUTPUT_TSV)"; \
	if [ -z "$$out" ]; then \
	  out="$(OUTPUT_DIR)/$(BACKEND)-$(ENGINE).tsv"; \
	fi; \
	if [ -n "$(strip $(EXPECTED_RIID_PODS))" ]; then export EXPECTED_RIID_PODS="$(EXPECTED_RIID_PODS)"; fi; \
	BACKEND="$(BACKEND)" \
	ENGINE="$(ENGINE)" \
	DATASET_FILE="$(DATASET_FILE)" \
	RUNTIME_ID="$(RUNTIME_ID)" \
	PODMAN_CLEAR_CACHE_BEFORE_PULL="$(PODMAN_CLEAR_CACHE_BEFORE_PULL)" \
	PODMAN_REGISTRY_PULL_HOST="$(PODMAN_REGISTRY_PULL_HOST)" \
	OUTPUT_TSV="$$out" \
	bash "$(PERF_DIR)/scenario/run-pull-scenario.sh"

# Шесть армов матрицы AGENT-99: три источника (riid | bare | dfinit) на два
# движка. Сценарий один, поэтому в имени цели он не участвует. Результат каждого
# арма ложится в $(OUTPUT_DIR)/<source>-<engine>.tsv и той же меткой
# подписывается колонка backend, иначе армы не различить в сводках.
riid-podman:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=riid ENGINE=podman

riid-containerd:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=riid ENGINE=containerd

bare-podman:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=bare ENGINE=podman

bare-containerd:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=bare ENGINE=containerd

dfinit-podman:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=dfinit ENGINE=podman

dfinit-containerd:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=dfinit ENGINE=containerd

clear-cluster-cache:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	bash "$(CLEAR_CLUSTER_SCRIPT)"

delete-local-registry-dataset:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	REG_URL="$(strip $(REGISTRY_URL))"; \
	if [ -z "$$REG_URL" ]; then \
	  kubectl -n registry-system port-forward svc/local-registry 5000:5000 >/dev/null 2>&1 & \
	  PF=$$!; \
	  trap 'kill $$PF 2>/dev/null || true' EXIT INT TERM; \
	  sleep 2; \
	  REG_URL=http://127.0.0.1:5000; \
	fi; \
	REGISTRY_URL="$$REG_URL" \
	DATASET_FILE="$(DATASET_FILE)" \
	DELETE_DATASET_RUN_GC="$(DELETE_DATASET_RUN_GC)" \
	DELETE_DATASET_DRY_RUN="$(DELETE_DATASET_DRY_RUN)" \
	bash "$(DELETE_REGISTRY_SCRIPT)"

# tc на registry-ноде (hostNetwork + privileged Pod): latency/bandwidth из config.yaml (.network_parameters).
registry-node-tc-apply:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	command -v yq >/dev/null || { echo "yq not found"; exit 1; }; \
	command -v bash >/dev/null || { echo "bash not found"; exit 1; }; \
	CLUSTER_CONFIG="$(GLOBAL_CONFIG_FILE)" bash "$(REGISTRY_TC_SCRIPT)" apply

registry-node-tc-clear:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	command -v bash >/dev/null || { echo "bash not found"; exit 1; }; \
	CLUSTER_CONFIG="$(GLOBAL_CONFIG_FILE)" bash "$(REGISTRY_TC_SCRIPT)" clear

# tc на всех нодах кластера (или NODE=имя): read-only qdisc/class show; exit 1 при stale tc на не-registry нодах.
registry-node-tc-check:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	command -v bash >/dev/null || { echo "bash not found"; exit 1; }; \
	CLUSTER_CONFIG="$(GLOBAL_CONFIG_FILE)" NODE="$(NODE)" bash "$(REGISTRY_TC_CHECK_SCRIPT)"

# Имя ноды registry и свободное место на хосте (kubectl debug + df /host), без SSH.
debug-registry-node:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)" >&2; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	NODE=$$(kubectl get nodes -l riid.registry=true -o jsonpath='{.items[0].metadata.name}' 2>/dev/null); \
	if [ -z "$$NODE" ]; then \
	  NODE=$$(kubectl -n registry-system get pod -l app.kubernetes.io/name=local-registry -o jsonpath='{.items[0].spec.nodeName}' 2>/dev/null); \
	fi; \
	if [ -z "$$NODE" ]; then \
	  NODE=$$(kubectl -n registry-system get pod -o wide --no-headers 2>/dev/null | awk '$$1 ~ /^local-registry-/ { print $$7; exit }'); \
	fi; \
	[ -n "$$NODE" ] || { echo "debug-registry-node: cannot resolve node (riid.registry=true, local-registry pod, or name ^local-registry-)" >&2; exit 1; }; \
	printf '%s\n' "node=$$NODE"; \
	kubectl debug "node/$$NODE" -i --image=busybox:1.36 -- sh -c 'df -h /host 2>/dev/null || df -h; echo ""; df -hi /host 2>/dev/null || df -hi'

# Сводка по паре армов: среднее на под, ratio, success rate, registry TX.
# Пара задаётся SUMMARY_A/SUMMARY_B — любые два TSV из OUTPUT_DIR.
# SUMMARY_OUT=path — записать в файл; иначе только stdout.
summarize:
	@if [ -n "$(SUMMARY_OUT)" ]; then \
	  out="$(SUMMARY_OUT)"; \
	  mkdir -p "$$(dirname "$$out")"; \
	  bash "$(SUMMARIZE_SCRIPT)" "$(SUMMARY_A)" "$(SUMMARY_B)" | tee "$$out"; \
	else \
	  bash "$(SUMMARIZE_SCRIPT)" "$(SUMMARY_A)" "$(SUMMARY_B)"; \
	fi

# Wall-clock кластера по AGGREGATE-строкам — основная сводка для recreate.
summarize-aggregate:
	@bash "$(AGGREGATE_SCRIPT)" "$(SUMMARY_A)" "$(SUMMARY_B)"
