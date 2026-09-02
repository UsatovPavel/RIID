# Local 3-node VirtualBox bench stand for AGENT-99.
SHELL := /bin/bash
HERE  := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

.PHONY: help up host identity prep cluster fixes verify recover down status cold-cache

help:
	@echo "make up       - build the whole stand from powered-off VMs"
	@echo "make recover  - VMs are off after a host reboot: bring them back"
	@echo "make verify   - assert the stand is actually usable"
	@echo "make cold-cache - valid cold state before an arm, ends in verify"
	@echo "make status   - short health summary"
	@echo "make down     - power the VMs off"
	@echo ""
	@echo "stages: host identity prep cluster fixes"

up: host identity prep cluster fixes verify

host:     ; @bash "$(HERE)01-host-prepare.sh"
identity: ; @bash "$(HERE)02-node-identity.sh"
prep:     ; @bash "$(HERE)03-node-prep.sh"
cluster:  ; @bash "$(HERE)04-cluster-init.sh"
fixes:    ; @bash "$(HERE)05-stand-fixes.sh"
verify:   ; @bash "$(HERE)06-verify.sh"
recover:  ; @bash "$(HERE)recover.sh"
cold-cache: ; @bash "$(HERE)cold-cache.sh"

status:
	@source "$(HERE)stand.env"; \
	echo "VMs running: $$(VBoxManage list runningvms | wc -l)/$$(echo $$STAND_VMS | wc -w)"; \
	KUBECONFIG=$$KUBECONFIG_OUT kubectl get nodes 2>/dev/null || echo "API not reachable"

down:
	@source "$(HERE)stand.env"; \
	for vm in $$STAND_VMS; do VBoxManage controlvm "$$vm" acpipowerbutton 2>/dev/null || true; done; \
	echo "power-off requested"
