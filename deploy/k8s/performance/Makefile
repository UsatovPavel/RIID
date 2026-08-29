CONFIG_FILE ?= ../providers/cluster/Selectel/serverConfig.yaml
KUBECONFIG := $(abspath $(CONFIG_FILE))
PERF_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
K8S := $(abspath $(PERF_DIR)/..)
GLOBAL_CONFIG_FILE := $(K8S)/config/config.yaml
REGISTRY_DIR := $(PERF_DIR)/registry
REGISTRY_TC_SCRIPT := $(REGISTRY_DIR)/registry-node-tc.sh
REGISTRY_TC_CHECK_SCRIPT := $(REGISTRY_DIR)/registry-node-tc-check.sh
INPUT_DIR := $(PERF_DIR)/input

# One scenario only — recreate, so MODE/CONCURRENCY are gone.
# BACKEND is where the image is pulled from, ENGINE is where it lands.
BACKEND ?= riid
ENGINE ?= podman
# Third coordinate of an arm, appended to the backend label and to the output
# file name. AGENT-74 needs it: riid+podman runs twice, with prefix import and
# without, and the two runs are otherwise identical.
ARM ?=
# true|false re-renders riid-config-secret with runtime.prefixImport pinned and
# restarts the DaemonSet; empty leaves the deployed config alone.
PREFIX_IMPORT ?=
RIID_PROFILE ?= $(shell yq -r '.test_registry_provider // "local"' $(GLOBAL_CONFIG_FILE) 2>/dev/null)
# EXPECTED_RIID_PODS — опционально; если пусто, run-pull-scenario.sh берёт cluster_topology.workers из deploy/k8s/config/config.yaml (0 = не проверять число подов).

# export, not a line in the env prefix of run: only the script needs the value,
# and a command-line override (make run SCENARIO=...) is exported by make anyway.
export SCENARIO ?= pr18
DATASET ?= A
DATASET_FILE ?=
OUTPUT_TSV ?=
CLEAR_CACHE_BEFORE_PULL ?= 0
# Local in-cluster registry; leave empty to pull from the Selectel registry.
REGISTRY_PULL_HOST = local-registry.registry-system.svc.cluster.local:5000

OUTPUT_DIR ?= $(PERF_DIR)/output
METRICS_SCRIPT := $(PERF_DIR)/summarize/scenario-metrics.sh
AGGREGATE_SCRIPT := $(PERF_DIR)/summarize/cluster-aggregate-time.sh
SUMMARY_OUT := $(OUTPUT_DIR)/summary.tsv
# Which arm to report metrics for; defaults to the arm this Makefile would run.
METRICS_TSV ?= $(OUTPUT_DIR)/$(BACKEND)-$(ENGINE)$(if $(strip $(ARM)),-$(strip $(ARM))).tsv
# Which pair of arms to compare wall-clock for: any two TSVs from OUTPUT_DIR.
SUMMARY_A ?= $(OUTPUT_DIR)/riid-podman.tsv
SUMMARY_B ?= $(OUTPUT_DIR)/bare-podman.tsv

ifeq ($(DATASET_FILE),)
  ifeq ($(DATASET),A)
    DATASET_FILE := $(INPUT_DIR)/dataset_a.tsv
  else ifeq ($(DATASET),B)
    DATASET_FILE := $(INPUT_DIR)/dataset_b.tsv
  endif
endif

.PHONY: run riid-podman riid-containerd riid-porto bare-podman bare-containerd bare-porto \
        dfinit-podman dfinit-containerd set-prefix-import \
        riid-podman-prefix riid-podman-noprefix \
        clear-cluster-cache delete-local-registry-dataset debug-registry-node \
        registry-node-tc-apply registry-node-tc-clear registry-node-tc-check metrics summarize-aggregate

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
	  out="$(OUTPUT_DIR)/$(BACKEND)-$(ENGINE)$(if $(strip $(ARM)),-$(strip $(ARM))).tsv"; \
	fi; \
	if [ -n "$(strip $(EXPECTED_RIID_PODS))" ]; then export EXPECTED_RIID_PODS="$(EXPECTED_RIID_PODS)"; fi; \
	BACKEND="$(BACKEND)" \
	ENGINE="$(ENGINE)" \
	ARM="$(strip $(ARM))" \
	DATASET_FILE="$(DATASET_FILE)" \
	CLEAR_CACHE_BEFORE_PULL="$(CLEAR_CACHE_BEFORE_PULL)" \
	REGISTRY_PULL_HOST="$(REGISTRY_PULL_HOST)" \
	OUTPUT_TSV="$$out" \
	bash "$(PERF_DIR)/scenario/run-pull-scenario.sh"

# The AGENT-99 matrix. Full test: three sources (riid | bare | dfinit) on podman
# and containerd. There is only one scenario, so it does not appear in the target
# name. Each arm lands in $(OUTPUT_DIR)/<source>-<engine>.tsv and the backend
# column carries the same label, otherwise the arms are indistinguishable in the
# summaries.
riid-podman:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=riid ENGINE=podman

riid-containerd:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=riid ENGINE=containerd

# AGENT-74: the same source and engine twice, differing only in prefix import.
# Each target re-pins the flag itself, so the two can be run in any order and a
# leftover setting from the previous arm cannot silently carry over.
riid-podman-prefix:
	@$(MAKE) -C "$(PERF_DIR)" set-prefix-import PREFIX_IMPORT=true
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=riid ENGINE=podman ARM=prefix

riid-podman-noprefix:
	@$(MAKE) -C "$(PERF_DIR)" set-prefix-import PREFIX_IMPORT=false
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=riid ENGINE=podman ARM=noprefix

# Renders riid-config-secret with runtime.prefixImport and waits for the rollout,
# so the pods that the next run measures are the ones carrying the new flag.
set-prefix-import:
	@[ -n "$(strip $(PREFIX_IMPORT))" ] || { echo "set PREFIX_IMPORT=true|false"; exit 1; }
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	RIID_PREFIX_IMPORT="$(strip $(PREFIX_IMPORT))" \
	  bash "$(K8S)/src/riid/render-riid-config-secret.sh" --apply --profile "$(RIID_PROFILE)"; \
	kubectl -n riid-system rollout restart daemonset riid; \
	kubectl -n riid-system rollout status daemonset riid

bare-podman:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=bare ENGINE=podman

bare-containerd:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=bare ENGINE=containerd

dfinit-podman:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=dfinit ENGINE=podman

dfinit-containerd:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=dfinit ENGINE=containerd

# Porto is a partial test: per AGENT-99 it has only RIID => Porto and bare Porto.
# There is deliberately no dfinit-porto target: dfinit cannot drive Porto, and
# engine/porto.inc.sh fails on those functions instead of silently degrading into
# a plain pull.
riid-porto:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=riid ENGINE=porto

bare-porto:
	@$(MAKE) -C "$(PERF_DIR)" run BACKEND=bare ENGINE=porto

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

# Three metrics of one arm: registry traffic, sum of per-image mean pull time,
# sum of per-image cluster wall-clock. Comparison is done by reading two runs
# side by side, not by the script.
# SUMMARY_OUT=path — also write to a file; otherwise stdout only.
metrics:
	@if [ -n "$(SUMMARY_OUT)" ]; then \
	  out="$(SUMMARY_OUT)"; \
	  mkdir -p "$$(dirname "$$out")"; \
	  bash "$(METRICS_SCRIPT)" "$(METRICS_TSV)" | tee "$$out"; \
	else \
	  bash "$(METRICS_SCRIPT)" "$(METRICS_TSV)"; \
	fi

# Cluster wall-clock from the AGGREGATE rows — the main summary for recreate.
summarize-aggregate:
	@bash "$(AGGREGATE_SCRIPT)" "$(SUMMARY_A)" "$(SUMMARY_B)"
