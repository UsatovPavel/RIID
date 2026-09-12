# Reproducible MKS stand for the RIID benchmark (AGENT-98).
# Credentials come from deploy/k8s/config/.env through tf.sh, never from tfvars.

TF_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
K8S_ROOT := $(abspath $(TF_DIR)/../../../..)
CONFIG_YAML ?= $(K8S_ROOT)/config/config.yaml
KUBECONFIG_OUT ?= $(abspath $(TF_DIR)/..)/serverConfig.yaml
# Обёртка одна на оба стенда и живёт в stand-common; каталог модуля она
# получает аргументом, поэтому TF раскрывается в два слова.
COMMON_DIR := $(abspath $(TF_DIR)/../stand-common)
TF := $(COMMON_DIR)/tf.sh $(TF_DIR)

# Single source of truth for the stand size: cluster_topology in config/config.yaml.
NODES ?= $(shell yq -r '.cluster_topology.workers + .cluster_topology.observers + .cluster_topology.registry_nodes + (.cluster_topology.dragonfly_infra_nodes // 0)' "$(CONFIG_YAML)" 2>/dev/null)

export NODES

NODE_READY_TIMEOUT ?= 1800

# Node boot disks detach only as Nova tears the servers down, and MKS keeps
# listing the cluster after terraform is done; purge waits for both.
WAIT_DETACH ?= 600
WAIT_CLUSTER_GONE ?= 900

# Scope of purge-volumes and of the destroy-time hooks in main.tf. Mirrors
# var.cluster_name, which purge cannot read back once the stand is destroyed;
# tf.sh turns it into TF_VAR_cluster_name, so the two cannot drift apart.
CLUSTER_NAME ?= riid-bench

export CLUSTER_NAME

.PHONY: help init fmt validate plan apply destroy delete-pvcs purge-volumes output project-id kubeconfig \
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
	@echo "make destroy       — delete this stand's PVCs, the stand, then its disks"
	@echo "make delete-pvcs   — delete this stand's PVCs so Cinder CSI frees their volumes"
	@echo "make purge-volumes — list this stand's leftover disks (CONFIRM=yes to delete)"

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

# Three steps, because neither end of the disk cleanup is terraform's to do.
# PVCs go first while the API still answers, so Cinder CSI releases its own
# volumes; then the stand; then the boot disks and whatever CSI left behind.
# Through a saved plan, like apply: `destroy -input=false` without
# -auto-approve dies on `error asking for approval: EOF`.
destroy:
	@$(MAKE) -C "$(TF_DIR)" delete-pvcs
	$(TF) plan -destroy -input=false -out=tfplan-destroy
	$(TF) apply -input=false tfplan-destroy
	@$(MAKE) -C "$(TF_DIR)" purge-volumes CONFIRM=yes

# Deletes this stand's PVCs through the written kubeconfig. Exits 0 when the
# cluster is already gone or the kubeconfig is stale - the volumes are then
# purge-volumes' problem, and a half-torn stand must not block destroy.
delete-pvcs:
	@bash "$(COMMON_DIR)/delete-stand-pvcs.sh" "$(KUBECONFIG_OUT)"

# List-only unless CONFIRM=yes, and only ever this stand's own volumes. MKS
# lists a destroyed cluster for minutes afterwards and CSI volumes are kept
# while it does, so wait for it to disappear before deciding they are orphans.
purge-volumes:
	@CONFIRM="$(CONFIRM)" STAND_PREFIX="$(CLUSTER_NAME)" \
	 WAIT_DETACH="$(WAIT_DETACH)" WAIT_CLUSTER_GONE="$(WAIT_CLUSTER_GONE)" \
	 bash "$(COMMON_DIR)/purge-stand-volumes.sh"

output:
	$(TF) output

# На эту цель ссылается сообщение валидации project_id в variables.tf.
project-id:
	@"$(COMMON_DIR)/resolve-project-id.sh"

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
