# Логи по одной цепочке pull: RIID pod + dfdaemon pod на той же ноде.
CONFIG_FILE ?= ../serverConfig.yaml
KUBECONFIG := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))$(CONFIG_FILE))

# Сколько "назад" смотреть логи (kubectl logs --since).
SINCE ?= 20m

# Опционально: фиксируем конкретную ноду. Иначе берётся нода первого RIID pod.
NODE ?=

# Файлы: сырой лог и полезная выжимка.
RAW_LOG ?= out.txt
SUMMARY_LOG ?= summary.txt

.PHONY: logs-riid-pull logs-dfdaemon-pull logs-pull-all logs-capture-pull logs-summarize-pull logs-capture-and-summarize

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
