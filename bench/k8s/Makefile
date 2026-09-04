# Local 3-node VirtualBox bench stand for AGENT-99.
SHELL := /bin/bash
HERE  := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

.PHONY: help up host identity prep cluster fixes verify recover down status cold-cache queue-install queue-start queue-status queue-stop queue-disable

help:
	@echo "make up       - build the whole stand from powered-off VMs"
	@echo "make recover  - VMs are off after a host reboot: bring them back"
	@echo "make verify   - assert the stand is actually usable"
	@echo "make cold-cache - valid cold state before an arm, ends in verify"
	@echo "make queue-start  - work the remaining arms, surviving reboots"
	@echo "make queue-status - what the queue has done and what is left"
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

queue-install:
	@mkdir -p "$(HOME)/.config/systemd/user" "$(HERE)queue-state"
	@cp "$(HERE)riid-arm-queue.service" "$(HOME)/.config/systemd/user/"
	@loginctl enable-linger "$(USER)" 2>/dev/null || true
	@systemctl --user daemon-reload
	@echo "installed; start with: make -C bench/k8s queue-start"

queue-start: queue-install
	@systemctl --user enable --now riid-arm-queue.service
	@systemctl --user status riid-arm-queue.service --no-pager | head -5

# stop != disable. Stopping to read a log used to leave the service disabled,
# and the queue simply never came back - an hour of stand time lost that way.
queue-stop:
	@systemctl --user stop riid-arm-queue.service 2>/dev/null || true
	@echo "stopped (still enabled); resume with: make -C bench/k8s queue-start"

queue-disable:
	@systemctl --user disable --now riid-arm-queue.service 2>/dev/null || true

queue-status:
	@systemctl --user is-active riid-arm-queue.service 2>/dev/null || echo "not running"
	@for f in "$(HERE)"queue-state/*.done; do [ -e "$$f" ] && echo "  DONE $$(basename $$f .done)"; done 2>/dev/null; true
	@tail -20 "$(HERE)queue-state/queue.log" 2>/dev/null || true

status:
	@source "$(HERE)stand.env"; \
	echo "VMs running: $$(VBoxManage list runningvms | wc -l)/$$(echo $$STAND_VMS | wc -w)"; \
	KUBECONFIG=$$KUBECONFIG_OUT kubectl get nodes 2>/dev/null || echo "API not reachable"

down:
	@source "$(HERE)stand.env"; \
	for vm in $$STAND_VMS; do VBoxManage controlvm "$$vm" acpipowerbutton 2>/dev/null || true; done; \
	echo "power-off requested"
