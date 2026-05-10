CONFIG_FILE ?= ../providers/cluster/Selectel/serverConfig.yaml
KUBECONFIG := $(abspath $(CONFIG_FILE))
PERF_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
INPUT_DIR := $(PERF_DIR)/input

MODE ?= rolling
BACKEND ?= riid
CONCURRENCY ?= 2
EXPECTED_RIID_PODS ?= 10

SCENARIO ?= pr18
DATASET ?= A
DATASET_FILE ?=
RUNTIME_ID ?= podman
OUTPUT_TSV ?=
PODMAN_CLEAR_CACHE_BEFORE_PULL ?= 0
PODMAN_REGISTRY_PULL_HOST=local-registry.registry-system.svc.cluster.local:5000
# for local registry. if need selectel registry set empty 

RIID_RESULTS ?= $(PERF_DIR)/output/prep-riid.tsv
PODMAN_RESULTS ?= $(PERF_DIR)/output/prep-podman.tsv
SUMMARIZE_SCRIPT := $(PERF_DIR)/summarize/summarize-riid-vs-podman.sh
SUMMARY_OUT := $(PERF_DIR)/output/summary.tsv

ifeq ($(DATASET_FILE),)
  ifeq ($(DATASET),A)
    DATASET_FILE := $(INPUT_DIR)/dataset_a.tsv
  else ifeq ($(DATASET),B)
    DATASET_FILE := $(INPUT_DIR)/dataset_b.tsv
  endif
endif

.PHONY: run rolling-riid recreate-riid rolling-podman recreate-podman clear-cluster-cache summarize

CLEAR_CLUSTER_SCRIPT := $(PERF_DIR)/scenario/clear/clear-cache-all-riid-pods.sh

run:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	out="$(OUTPUT_TSV)"; \
	if [ -z "$$out" ]; then \
	  if [ "$(BACKEND)" = "riid" ]; then \
	    out="$(RIID_RESULTS)"; \
	  else \
	    out="$(PODMAN_RESULTS)"; \
	  fi; \
	fi; \
	MODE="$(MODE)" \
	BACKEND="$(BACKEND)" \
	CONCURRENCY="$(CONCURRENCY)" \
	EXPECTED_RIID_PODS="$(EXPECTED_RIID_PODS)" \
	SCENARIO="$(SCENARIO)" \
	DATASET_FILE="$(DATASET_FILE)" \
	RUNTIME_ID="$(RUNTIME_ID)" \
	PODMAN_CLEAR_CACHE_BEFORE_PULL="$(PODMAN_CLEAR_CACHE_BEFORE_PULL)" \
	PODMAN_REGISTRY_PULL_HOST="$(PODMAN_REGISTRY_PULL_HOST)" \
	OUTPUT_TSV="$$out" \
	bash "$(PERF_DIR)/scenario/run-pull-scenario.sh"

rolling-riid:
	@$(MAKE) -C "$(PERF_DIR)" run MODE=rolling BACKEND=riid SCENARIO="$(SCENARIO)-rolling-riid"

recreate-riid:
	@$(MAKE) -C "$(PERF_DIR)" run MODE=recreate BACKEND=riid SCENARIO="$(SCENARIO)-recreate-riid"

rolling-podman:
	@$(MAKE) -C "$(PERF_DIR)" run MODE=rolling BACKEND=podman SCENARIO="$(SCENARIO)-rolling-podman"

recreate-podman:
	@$(MAKE) -C "$(PERF_DIR)" run MODE=recreate BACKEND=podman SCENARIO="$(SCENARIO)-recreate-podman"

clear-cluster-cache:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	bash "$(CLEAR_CLUSTER_SCRIPT)"

# Сводка T_riid / T_podman / ratio / success rate по двум runner-CSV (см. summarize/summarize-riid-vs-podman.sh).
# SUMMARY_OUT=path — записать в файл; иначе только stdout.
summarize:
	@if [ -n "$(SUMMARY_OUT)" ]; then \
	  out="$(SUMMARY_OUT)"; \
	  mkdir -p "$$(dirname "$$out")"; \
	  bash "$(SUMMARIZE_SCRIPT)" "$(RIID_RESULTS)" "$(PODMAN_RESULTS)" | tee "$$out"; \
	else \
	  bash "$(SUMMARIZE_SCRIPT)" "$(RIID_RESULTS)" "$(PODMAN_RESULTS)"; \
	fi
