SHELL := /bin/bash

# С хоста (yc compute ssh). Переопределение: make bootstrap-on-server YC_INSTANCE_ID=...
YC_INSTANCE_ID ?= fv4jo9irtdjgr0n048es
YC_IDENTITY ?= $(HOME)/.ssh/ssh-key-1775683235717
YC_LOGIN ?= user
# Домашний каталог на ВМ для push-secrets (переопредели, если не /home/<логин>).
YC_REMOTE_HOME ?= /home/$(YC_LOGIN)
# Каталог клона Riid на ВМ.
YC_RIID_DIR ?= $(YC_REMOTE_HOME)/RIID

YANDEX_CLOUD_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
ENV_FILE := $(YANDEX_CLOUD_DIR).env

YC_SSH = yc compute ssh --id $(YC_INSTANCE_ID) --identity-file $(YC_IDENTITY) --login $(YC_LOGIN)

.PHONY: connect help bootstrap-on-server push-secrets-on-server install-on-server minikube-start-on-server test-sequential-30 test-scenario-a

help:
	@echo "connect                  — с хоста: SSH на ВМ"
	@echo "bootstrap-on-server    — с хоста: только ОС/стек на ВМ (без секретов, .env не нужен)"
	@echo "push-secrets-on-server — GPR + dockerhub.env + podman/docker login docker.io"
	@echo "install-on-server      — bootstrap-on-server + push-secrets-on-server"
	@echo "minikube-start-on-server"
	@echo "test-sequential-30      — с хоста: PR15 b1 (30 образов) на ВМ"
	@echo "test-scenario-a         — с хоста: PR15 (a) median за 5, library/irssi на ВМ"
	@echo "Grafana: на ВМ в корне клона Riid: make metrics-stack-create"

connect:
	yc compute ssh \
	--id $(YC_INSTANCE_ID) \
	--identity-file $(YC_IDENTITY) \
	--login $(YC_LOGIN)

# Только установка пакетов на ВМ; не читает .env и не трогает ключи.
bootstrap-on-server:
	cat "$(YANDEX_CLOUD_DIR)bootstrap-server.sh" | $(YC_SSH) -- bash -s install

# На хосте: $(ENV_FILE). На ВМ: ~/.gradle/gradle.properties, ~/.riid/dockerhub.env,
# при DOCKERHUB_* — podman и docker login docker.io (Grafana stack / podman pull).
push-secrets-on-server:
	@set -euo pipefail; \
	test -f "$(ENV_FILE)" || exit 1; \
	set -a; . "$(ENV_FILE)"; set +a; \
	ACT="$$GITHUB_ACTOR"; KEY="$$GITHUB_PACKAGES_TOKEN"; \
	[ -n "$$ACT" ] || ACT="$$GPR_USER"; \
	[ -n "$$KEY" ] || KEY="$$GPR_KEY"; \
	[ -n "$$ACT" ] && [ -n "$$KEY" ] || exit 1; \
	if [ -n "$$DOCKERHUB_USER" ] || [ -n "$$DOCKERHUB_TOKEN" ]; then \
	  [ -n "$$DOCKERHUB_USER" ] && [ -n "$$DOCKERHUB_TOKEN" ] || exit 1; \
	fi; \
	$(YC_SSH) -- mkdir -p "$(YC_REMOTE_HOME)/.gradle"; \
	printf 'gpr.user=%s\ngpr.key=%s\n' "$$ACT" "$$KEY" | $(YC_SSH) -- tee "$(YC_REMOTE_HOME)/.gradle/gradle.properties" >/dev/null; \
	$(YC_SSH) -- chmod 600 "$(YC_REMOTE_HOME)/.gradle/gradle.properties"; \
	if [ -n "$$DOCKERHUB_USER" ] && [ -n "$$DOCKERHUB_TOKEN" ]; then \
	  $(YC_SSH) -- mkdir -p "$(YC_REMOTE_HOME)/.riid"; \
	  printf 'DOCKERHUB_USER=%s\nDOCKERHUB_TOKEN=%s\n' "$$DOCKERHUB_USER" "$$DOCKERHUB_TOKEN" | $(YC_SSH) -- tee "$(YC_REMOTE_HOME)/.riid/dockerhub.env" >/dev/null; \
	  $(YC_SSH) -- chmod 600 "$(YC_REMOTE_HOME)/.riid/dockerhub.env"; \
	  printf '%s\n' "$$DOCKERHUB_TOKEN" | $(YC_SSH) -- podman login docker.io -u "$$DOCKERHUB_USER" --password-stdin; \
	  printf '%s\n' "$$DOCKERHUB_TOKEN" | $(YC_SSH) -- docker login docker.io -u "$$DOCKERHUB_USER" --password-stdin; \
	fi

install-on-server: bootstrap-on-server push-secrets-on-server

minikube-start-on-server:
	cat "$(YANDEX_CLOUD_DIR)bootstrap-server.sh" | $(YC_SSH) -- bash -s minikube-start


# С хоста: Daemon30ImagesSequentialPullTest на ВМ (демон, podman, curl на ВМ; при необходимости YC_RIID_DIR=…).
test-sequential-30:
	printf '%s\n' \
		'set -euo pipefail' \
		'cd "$(YC_RIID_DIR)"' \
		'set -a; [ -f config/.env ] && . config/.env; [ -f "$$HOME/.riid/dockerhub.env" ] && . "$$HOME/.riid/dockerhub.env"; set +a' \
		'./gradlew performanceTest --tests riid.performance.sequentially.manyimages.Daemon30ImagesSequentialPullTest' \
		| $(YC_SSH) -- bash -s

# С хоста: PR15 (a) — 5 итераций RIID + 5 podman pull, медианы в логе Gradle.
test-scenario-a:
	printf '%s\n' \
		'set -euo pipefail' \
		'cd "$(YC_RIID_DIR)"' \
		'set -a; [ -f config/.env ] && . config/.env; [ -f "$$HOME/.riid/dockerhub.env" ] && . "$$HOME/.riid/dockerhub.env"; set +a' \
		'./gradlew performanceTest --tests riid.performance.sequentially.oneimage.DaemonBenchmarkColdCachePullsTest' \
		| $(YC_SSH) -- bash -s