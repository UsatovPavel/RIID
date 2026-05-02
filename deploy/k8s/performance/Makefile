CONFIG_FILE ?= ../Selectel/serverConfig.yaml
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
OUTPUT_CSV ?=
PODMAN_CLEAR_CACHE_BEFORE_PULL ?= 0

ifeq ($(DATASET_FILE),)
  ifeq ($(DATASET),A)
    DATASET_FILE := $(INPUT_DIR)/dataset_a.tsv
  else ifeq ($(DATASET),B)
    DATASET_FILE := $(INPUT_DIR)/dataset_b.tsv
  endif
endif

.PHONY: run rolling-riid recreate-riid rolling-podman recreate-podman

run:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	MODE="$(MODE)" \
	BACKEND="$(BACKEND)" \
	CONCURRENCY="$(CONCURRENCY)" \
	EXPECTED_RIID_PODS="$(EXPECTED_RIID_PODS)" \
	SCENARIO="$(SCENARIO)" \
	DATASET_FILE="$(DATASET_FILE)" \
	RUNTIME_ID="$(RUNTIME_ID)" \
	PODMAN_CLEAR_CACHE_BEFORE_PULL="$(PODMAN_CLEAR_CACHE_BEFORE_PULL)" \
	OUTPUT_CSV="$(OUTPUT_CSV)" \
	bash "$(PERF_DIR)/scenario/run-pull-scenario.sh"

rolling-riid:
	@$(MAKE) -C "$(PERF_DIR)" run MODE=rolling BACKEND=riid SCENARIO="$(SCENARIO)-rolling-riid"

recreate-riid:
	@$(MAKE) -C "$(PERF_DIR)" run MODE=recreate BACKEND=riid SCENARIO="$(SCENARIO)-recreate-riid"

rolling-podman:
	@$(MAKE) -C "$(PERF_DIR)" run MODE=rolling BACKEND=podman SCENARIO="$(SCENARIO)-rolling-podman"

recreate-podman:
	@$(MAKE) -C "$(PERF_DIR)" run MODE=recreate BACKEND=podman SCENARIO="$(SCENARIO)-recreate-podman"
