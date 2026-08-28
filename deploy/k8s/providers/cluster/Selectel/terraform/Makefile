# Reproducible MKS stand for the RIID benchmark (AGENT-98).
# Credentials come from deploy/k8s/config/.env through tf.sh, never from tfvars.

TF_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
K8S_ROOT := $(abspath $(TF_DIR)/../../../..)
CONFIG_YAML ?= $(K8S_ROOT)/config/config.yaml
KUBECONFIG_OUT ?= $(abspath $(TF_DIR)/..)/serverConfig.yaml
TF := $(TF_DIR)/tf.sh

# Single source of truth for the stand size: cluster_topology in config/config.yaml.
NODES ?= $(shell yq -r '.cluster_topology.workers + .cluster_topology.observers + .cluster_topology.registry_nodes' "$(CONFIG_YAML)" 2>/dev/null)

export NODES

.PHONY: help init fmt validate plan apply destroy output kubeconfig

help:
	@echo "make init          — download the selectel provider"
	@echo "make validate      — offline check of the configuration"
	@echo "make plan          — what would be created in Selectel (NODES=$(NODES))"
	@echo "make apply         — create the cluster and the node group"
	@echo "make kubeconfig    — write $(KUBECONFIG_OUT)"
	@echo "make destroy       — delete the stand"

init:
	$(TF) init -input=false

fmt:
	$(TF) fmt -recursive

validate:
	$(TF) validate

plan:
	$(TF) plan -input=false -out=tfplan

apply:
	$(TF) apply -input=false tfplan

destroy:
	$(TF) destroy -input=false

output:
	$(TF) output

# bootstrap/Makefile reads the cluster through CONFIG_FILE, which defaults to this path.
kubeconfig:
	@umask 077 && $(TF) output -raw kubeconfig > "$(KUBECONFIG_OUT)"
	@echo "kubeconfig: $(KUBECONFIG_OUT)"
