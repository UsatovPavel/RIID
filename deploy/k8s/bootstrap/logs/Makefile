# logs-bundle читает TSV из kubectl циклом с IFS=$'\t', а это bash-синтаксис:
# под dash пустая метка component сдвинула бы поля и лог ушёл бы не в тот каталог.
SHELL := /bin/bash

# Логи по одной цепочке pull: RIID pod + dfdaemon pod на той же ноде.
# Kubeconfig: как в performance/Makefile (Selectel). Переопределение: CONFIG_FILE=path/to/kubeconfig
CONFIG_FILE ?= ../../providers/cluster/Selectel/serverConfig.yaml
KUBECONFIG := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))$(CONFIG_FILE))

# Сколько "назад" смотреть логи (kubectl logs --since).
SINCE ?= 20m

# Опционально: фиксируем конкретную ноду. Иначе берётся нода первого RIID pod.
NODE ?=

# Опционально: фильтр для scheduler-логов (например IMAGE_FILTER='riid/bitnami/mongodb').
IMAGE_FILTER ?=

# Файлы: сырой лог и полезная выжимка.
RAW_LOG ?= out.txt
SUMMARY_LOG ?= summary.txt

# Куда logs-bundle складывает логи всего прогона.
BUNDLE_DIR ?= $(abspath $(dir $(lastword $(MAKEFILE_LIST)))../../performance/output/logs)

.PHONY: logs-riid-pull logs-dfdaemon-pull logs-dfscheduler-pull logs-pull-all logs-capture-pull logs-summarize-pull logs-capture-and-summarize logs-bundle

logs-riid-pull:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	if [ -n "$(NODE)" ]; then \
	  RIID_POD=$$(kubectl -n riid-system get pods -l app.kubernetes.io/name=riid --field-selector=spec.nodeName=$(NODE) -o jsonpath='{.items[0].metadata.name}'); \
	else \
	  RIID_POD=$$(kubectl -n riid-system get pods -l app.kubernetes.io/name=riid -o jsonpath='{.items[0].metadata.name}'); \
	fi; \
	[ -n "$$RIID_POD" ] || { echo "logs-riid-pull: no RIID pod found"; exit 1; }; \
	echo "RIID_POD=$$RIID_POD SINCE=$(SINCE)"; \
	kubectl -n riid-system logs "$$RIID_POD" -c riid --since=$(SINCE) --timestamps

logs-dfdaemon-pull:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	if [ -n "$(NODE)" ]; then \
	  NODE_NAME="$(NODE)"; \
	else \
	  RIID_POD=$$(kubectl -n riid-system get pods -l app.kubernetes.io/name=riid -o jsonpath='{.items[0].metadata.name}'); \
	  [ -n "$$RIID_POD" ] || { echo "logs-dfdaemon-pull: no RIID pod found"; exit 1; }; \
	  NODE_NAME=$$(kubectl -n riid-system get pod "$$RIID_POD" -o jsonpath='{.spec.nodeName}'); \
	fi; \
	DF_POD=$$(kubectl -n dragonfly-system get pods -l app=dragonfly,component=client --field-selector=spec.nodeName=$$NODE_NAME -o jsonpath='{.items[0].metadata.name}'); \
	[ -n "$$DF_POD" ] || { echo "logs-dfdaemon-pull: no dfdaemon pod on node=$$NODE_NAME"; exit 1; }; \
	echo "NODE=$$NODE_NAME DF_POD=$$DF_POD SINCE=$(SINCE)"; \
	kubectl -n dragonfly-system logs "$$DF_POD" --since=$(SINCE) --timestamps

# Логи Dragonfly scheduler: как назначаются/трекатся задачи скачивания image.
# Пример:
#   make -C deploy/k8s/bootstrap/logs logs-dfscheduler-pull SINCE=30m
#   make -C deploy/k8s/bootstrap/logs logs-dfscheduler-pull SINCE=30m IMAGE_FILTER='riid/grafana/loki'
logs-dfscheduler-pull:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	SCHED_POD=$$(kubectl -n dragonfly-system get pods -l app=dragonfly,component=scheduler -o jsonpath='{.items[0].metadata.name}'); \
	[ -n "$$SCHED_POD" ] || { echo "logs-dfscheduler-pull: no scheduler pod found"; exit 1; }; \
	echo "SCHED_POD=$$SCHED_POD SINCE=$(SINCE) IMAGE_FILTER=$(IMAGE_FILTER)"; \
	if [ -n "$(IMAGE_FILTER)" ]; then \
	  kubectl -n dragonfly-system logs "$$SCHED_POD" --since=$(SINCE) --timestamps | awk '/task|peer|download|image|manifest|digest/ {print}' | grep -F "$(IMAGE_FILTER)" || true; \
	else \
	  kubectl -n dragonfly-system logs "$$SCHED_POD" --since=$(SINCE) --timestamps | awk '/task|peer|download|image|manifest|digest/ {print}' || true; \
	fi

logs-pull-all:
	@$(MAKE) logs-riid-pull SINCE="$(SINCE)" NODE="$(NODE)"
	@echo "-----"
	@$(MAKE) logs-dfdaemon-pull SINCE="$(SINCE)" NODE="$(NODE)"

# Сохранить сырые логи RIID+dfdaemon в один файл.
logs-capture-pull:
	@$(MAKE) logs-pull-all SINCE="$(SINCE)" NODE="$(NODE)" > "$(RAW_LOG)"
	@echo "saved raw logs to $(RAW_LOG)"

# Построить краткую выжимку из RAW_LOG в SUMMARY_LOG.
logs-summarize-pull:
	@[ -f "$(RAW_LOG)" ] || { echo "logs-summarize-pull: $(RAW_LOG) not found (run logs-capture-pull)"; exit 1; }; \
	{ \
	  echo "# Pull log summary"; \
	  echo "generated_at=$$(date -Iseconds)"; \
	  echo "source=$(RAW_LOG)"; \
	  echo "since=$(SINCE)"; \
	  echo; \
	  echo "## Trace IDs"; \
	  awk 'match($$0,/traceId=[0-9a-f-]{36}/){print substr($$0,RSTART+8,RLENGTH-8)}' "$(RAW_LOG)" | awk '!seen[$$0]++' || true; \
	  echo; \
	  echo "## RIID key events"; \
	  awk '/pull\.handle\.(trace|execute\.start|success|exception|unmapped_error|timeout|exit)/ || /P2P fetch failed/ || /downloaded layer .* from registry/ || /Source selected:/{print}' "$(RAW_LOG)" || true; \
	  echo; \
	  echo "## Dragonfly key events"; \
	  awk '/dfdaemon/ || /starting download task/ || /download task completed/ || /download task stream completed without finished marker/ || /backend error: 401/ || /Unauthorized/{print}' "$(RAW_LOG)" || true; \
	  echo; \
	  echo "## Runtime/Import failures"; \
	  awk '/OCI layout build failed/ || /Runtime import/ || /Broken pipe/ || /overlay is not supported over overlayfs/ || /Failed to load image/{print}' "$(RAW_LOG)" || true; \
	  echo; \
	  echo "## WARN/ERROR lines"; \
	  awk '/\"level\":\"WARN\"|\"level\":\"ERROR\"| level=warn | level=error /{print}' "$(RAW_LOG)" || true; \
	} > "$(SUMMARY_LOG)"
	@echo "saved summary to $(SUMMARY_LOG)"

# Полный цикл: собрать сырые логи и сразу сделать выжимку.
logs-capture-and-summarize:
	@$(MAKE) logs-capture-pull SINCE="$(SINCE)" NODE="$(NODE)" RAW_LOG="$(RAW_LOG)"
	@$(MAKE) logs-summarize-pull SINCE="$(SINCE)" RAW_LOG="$(RAW_LOG)" SUMMARY_LOG="$(SUMMARY_LOG)"

# AGENT-74 requires the dfdaemon, scheduler and seed-client logs on the
# workstation, and per-pod targets above cover one node at a time. This walks
# every dragonfly pod by its component label instead of naming the components,
# so a chart that renames one of them shows up as an extra directory rather than
# as a silently missing arm. RIID pods come along: they are the other half of
# every pull chain.
logs-bundle:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX" >/dev/null; \
	OUT="$(BUNDLE_DIR)"; \
	mkdir -p "$$OUT"; \
	: > "$$OUT/manifest.txt"; \
	kubectl -n dragonfly-system get pods -l app=dragonfly \
	  -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.labels.component}{"\t"}{.spec.nodeName}{"\n"}{end}' \
	| while IFS=$$'\t' read -r pod component node; do \
	    [ -n "$$pod" ] || continue; \
	    comp="$${component:-unlabelled}"; \
	    mkdir -p "$$OUT/$$comp"; \
	    printf '%s\t%s\t%s\n' "dragonfly-system/$$pod" "component=$$comp" "node=$$node" >> "$$OUT/manifest.txt"; \
	    kubectl -n dragonfly-system logs "$$pod" --all-containers --since=$(SINCE) --timestamps \
	      > "$$OUT/$$comp/$$pod.log" 2>"$$OUT/$$comp/$$pod.err" || true; \
	    [ -s "$$OUT/$$comp/$$pod.err" ] || rm -f "$$OUT/$$comp/$$pod.err"; \
	  done; \
	mkdir -p "$$OUT/riid"; \
	kubectl -n riid-system get pods -l app.kubernetes.io/name=riid \
	  -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.nodeName}{"\n"}{end}' \
	| while IFS=$$'\t' read -r pod node; do \
	    [ -n "$$pod" ] || continue; \
	    printf '%s\t%s\n' "riid-system/$$pod" "node=$$node" >> "$$OUT/manifest.txt"; \
	    kubectl -n riid-system logs "$$pod" -c riid --since=$(SINCE) --timestamps \
	      > "$$OUT/riid/$$pod.log" 2>/dev/null || true; \
	  done; \
	echo "logs-bundle: SINCE=$(SINCE) -> $$OUT"; \
	find "$$OUT" -name '*.log' -printf '%p\t%s\n' | sort
