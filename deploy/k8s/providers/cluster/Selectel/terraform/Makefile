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

NODE_READY_TIMEOUT ?= 1800

.PHONY: help init fmt validate plan apply destroy output kubeconfig \
        stand stand-6 stand-12 wait nodes

help:
	@echo "make stand         — init + plan + apply + kubeconfig + wait + nodes (NODES=$(NODES))"
	@echo "make stand-6       — the same on 6 nodes"
	@echo "make stand-12      — the same on 12 nodes"
	@echo "make init          — download the selectel provider"
	@echo "make validate      — offline check of the configuration"
	@echo "make plan          — what would be created in Selectel (NODES=$(NODES))"
	@echo "make apply         — create the cluster and the node group"
	@echo "make kubeconfig    — write $(KUBECONFIG_OUT)"
	@echo "make wait          — block until every node is Ready"
	@echo "make nodes         — kubectl get nodes through the written kubeconfig"
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

# One button: from an empty project to a cluster whose nodes are Ready and whose
# kubeconfig is on disk, ready for `make -C bootstrap install-all`.
stand:
	@$(MAKE) -C "$(TF_DIR)" init
	@$(MAKE) -C "$(TF_DIR)" plan
	@$(MAKE) -C "$(TF_DIR)" apply
	@$(MAKE) -C "$(TF_DIR)" kubeconfig
	@$(MAKE) -C "$(TF_DIR)" wait
	@$(MAKE) -C "$(TF_DIR)" nodes

stand-6:
	@$(MAKE) -C "$(TF_DIR)" stand NODES=6

stand-12:
	@$(MAKE) -C "$(TF_DIR)" stand NODES=12

nodes:
	@KUBECONFIG="$(KUBECONFIG_OUT)" kubectl get nodes -o wide

# Node group returns from the API before its nodes register, so apply finishing
# is not the same as a usable cluster. Control plane отбрасывается селектором по
# наличию метки, а не по её значению: kubeadm ставит
# node-role.kubernetes.io/control-plane с пустым значением, и сравнение значения
# посчитало бы мастер обычным воркером.
wait:
	@[ -n "$(strip $(NODES))" ] || { echo "NODES is empty: install yq or pass NODES=6" >&2; exit 1; }
	@KUBECONFIG="$(KUBECONFIG_OUT)"; export KUBECONFIG; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (run make kubeconfig)" >&2; exit 1; }; \
	deadline=$$(( $$(date +%s) + $(NODE_READY_TIMEOUT) )); \
	while :; do \
	  ready=$$(kubectl get nodes --no-headers \
	    -l '!node-role.kubernetes.io/control-plane,!node-role.kubernetes.io/master' 2>/dev/null \
	    | awk '$$2 == "Ready"' | wc -l); \
	  echo "ready $$ready/$(NODES)"; \
	  [ "$$ready" -ge "$(NODES)" ] && break; \
	  [ "$$(date +%s)" -lt "$$deadline" ] || { echo "timeout waiting for nodes" >&2; exit 1; }; \
	  sleep 20; \
	done
