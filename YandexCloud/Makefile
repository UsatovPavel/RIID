SHELL := /bin/bash

# С хоста (yc compute ssh). Переопределение: make install-on-server YC_INSTANCE_ID=...
YC_INSTANCE_ID ?= fv4jo9irtdjgr0n048es
YC_IDENTITY ?= $(HOME)/.ssh/ssh-key-1775683235717
YC_LOGIN ?= user

YANDEX_CLOUD_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))

YC_SSH = yc compute ssh --id $(YC_INSTANCE_ID) --identity-file $(YC_IDENTITY) --login $(YC_LOGIN)

.PHONY: connect install-on-server minikube-start-on-server help

help:
	@echo "connect                 — с хоста: SSH на ВМ"
	@echo "install-on-server      — с хоста: прогон bootstrap-server.sh на ВМ по SSH"
	@echo "minikube-start-on-server — с хоста: minikube-start на ВМ"
	@echo "Grafana stack: на ВМ в корне клона Riid: make metrics-stack-create"

connect:
	yc compute ssh \
	--id $(YC_INSTANCE_ID) \
	--identity-file $(YC_IDENTITY) \
	--login $(YC_LOGIN)

# С хоста: передать скрипт на ВМ по stdin удалённого bash.
install-on-server:
	cat "$(YANDEX_CLOUD_DIR)bootstrap-server.sh" | $(YC_SSH) -- bash -s install

# После install-on-server.
minikube-start-on-server:
	cat "$(YANDEX_CLOUD_DIR)bootstrap-server.sh" | $(YC_SSH) -- bash -s minikube-start
