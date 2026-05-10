# Selectel — kubeconfig: локальный файл (по умолчанию serverConfig.yaml; при смене аренды часто тот же файл, контекст внутри меняется).
CONFIG_FILE ?= serverConfig.yaml
SELECTEL_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
K8S := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/..)
OBSERVER_CHART := $(K8S)/monitoring/helm/riid-observer
KUBECONFIG := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))$(CONFIG_FILE))
GLOBAL_CONFIG_FILE := $(K8S)/config/config.yaml
INFRA_VERSIONS_FILE := $(K8S)/config/infra_versions.yaml
DRAGONFLY_REGISTRY_DIR := $(K8S)/src/dragonfly/registry
DRAGONFLY_PULL_SECRET_SCRIPT := $(DRAGONFLY_REGISTRY_DIR)/setup-dockerhub-pull-secret.sh

# OpenStack Cinder: класс по умолчанию для PVC без storageClass (Helm Dragonfly / Bitnami MySQL+Redis).
# Переопределить: make storage-default STORAGE_CLASS=другой-класс
STORAGE_CLASS ?= fast.ru-3b
RIID_WAIT_INTERVAL ?= 15
RIID_WAIT_TIMEOUT ?= 600
MONITORING_NODE ?=
REGISTRY_NODE ?=
# Образ DaemonSet должен уже содержать podman+fuse-overlayfs+curl+jq (+ storage/registries как в Dockerfile.worker или экв.).
# На нодах ничего не ставим; worker-tooling, local-registry и профили registry — bootstrap/registry/Makefile.
.PHONY: connect nodes validate-cluster-state mark-monitoring-node-auto mark-registry-node-auto storage-default install-dragonfly verify-riid-pod-tooling install-riid wait-riid-ready install-metrics-collector wait-metrics-collector sync-monitoring-observer-chart install-monitoring-observer wait-monitoring-observer smoke-download smoke-download-all install-all rollout-riid-image-from-manifest

# Подключает контекст из current-context в kubeconfig, затем shell с тем же KUBECONFIG.
connect:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	exec "$${SHELL:-bash}"

# Быстрая проверка: контекст + ноды
nodes:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	kubectl get nodes -o wide

# Проверяет соответствие текущего числа worker-нод глобальному config.yaml (cluster_topology.workers).
validate-cluster-state:
	@command -v yq >/dev/null || { echo "yq not found"; exit 1; }
	@[ -f "$(GLOBAL_CONFIG_FILE)" ] || { echo "global config not found: $(GLOBAL_CONFIG_FILE)"; exit 1; }
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	EXPECTED_WORKERS=$$(yq e '.cluster_topology.workers' "$(GLOBAL_CONFIG_FILE)"); \
	[ "$$EXPECTED_WORKERS" != "null" ] && [ -n "$$EXPECTED_WORKERS" ] || { echo "cluster_topology.workers is empty in $(GLOBAL_CONFIG_FILE)"; exit 1; }; \
	ACTUAL_WORKERS=$$(kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.labels.node-role\.kubernetes\.io/control-plane}{"\t"}{.metadata.labels.node-role\.kubernetes\.io/master}{"\n"}{end}' | awk '$$1=="" && $$2=="" {c++} END {print c+0}'); \
	echo "validate-cluster-state: workers actual=$$ACTUAL_WORKERS expected=$$EXPECTED_WORKERS"; \
	[ "$$ACTUAL_WORKERS" -eq "$$EXPECTED_WORKERS" ] || { echo "workers mismatch: expected=$$EXPECTED_WORKERS actual=$$ACTUAL_WORKERS"; exit 1; }

# Автовыбор monitoring-ноды: последняя worker-нода (не control-plane/master).
# Если задан MONITORING_NODE, используется он.
mark-monitoring-node-auto:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	if [ -n "$(MONITORING_NODE)" ]; then \
	  NODE="$(MONITORING_NODE)"; \
	else \
	  NODE=$$(kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.labels.node-role\.kubernetes\.io/control-plane}{"\t"}{.metadata.labels.node-role\.kubernetes\.io/master}{"\n"}{end}' \
	    | awk '$$2=="" && $$3=="" {print $$1}' | tail -n1); \
	fi; \
	[ -n "$$NODE" ] || { echo "mark-monitoring-node-auto: no worker node found"; exit 1; }; \
	kubectl label node --all riid.monitoring- >/dev/null 2>&1 || true; \
	kubectl label node "$$NODE" riid.monitoring=true --overwrite; \
	echo "monitoring node: $$NODE"; \
	kubectl get node "$$NODE" --show-labels | sed -n '1p;/riid.monitoring/p'

# Автовыбор registry-ноды: первый worker, который не помечен как monitoring.
# Если задан REGISTRY_NODE, используется он.
mark-registry-node-auto:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	if [ -n "$(REGISTRY_NODE)" ]; then \
	  NODE="$(REGISTRY_NODE)"; \
	else \
	  NODE=$$(kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.labels.node-role\.kubernetes\.io/control-plane}{"\t"}{.metadata.labels.node-role\.kubernetes\.io/master}{"\t"}{.metadata.labels.riid\.monitoring}{"\n"}{end}' \
	    | awk '$$2=="" && $$3=="" && $$4!="true" {print $$1}' | head -n1); \
	fi; \
	[ -n "$$NODE" ] || { echo "mark-registry-node-auto: no worker node (excluding monitoring) found"; exit 1; }; \
	kubectl label node --all riid.registry- >/dev/null 2>&1 || true; \
	kubectl label node "$$NODE" riid.registry=true --overwrite; \
	echo "registry node: $$NODE"; \
	kubectl get node "$$NODE" --show-labels | sed -n '1p;/riid.registry/p'

# Пометить Cinder StorageClass как default (нужно для Dragonfly Helm на этом стенде).
storage-default:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	kubectl patch storageclass $(STORAGE_CLASS) \
		-p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'

# Новый конфиг Dragonfly: правите repo root scripts/values.yaml, затем снова эта цель (helm upgrade --install).
# Перед helm: Docker Hub imagePullSecret в dragonfly-system (см. также bootstrap/registry/Makefile для профилей registry).
install-dragonfly:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	bash "$(DRAGONFLY_PULL_SECRET_SCRIPT)"
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	bash "$(K8S)/src/dragonfly/install-dragonfly.sh"

# Работает из любой cwd; манифесты: $(K8S)/…
# Secret riid-config-secret из $(K8S)/config/.env — только bootstrap/registry/Makefile (secret-config-riid / registry-apply-profile).
install-riid:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	kubectl apply -f "$(K8S)/namespace.yaml"; \
	kubectl apply -f "$(K8S)/riid/configmap.yaml"; \
	if [ -f "$(K8S)/config/.env" ]; then \
	  echo "install-riid: есть $(K8S)/config/.env — при необходимости примените Secret из bootstrap/registry/Makefile (secret-config-riid)." >&2; \
	else \
	  echo "install-riid: нет $(K8S)/config/.env — пропуск riid-config-secret (см. config/.env.example)" >&2; \
	fi; \
	kubectl apply -f "$(K8S)/riid/daemonset.yaml"; \
	kubectl apply -f "$(K8S)/riid/service.yaml"

# Ждём, пока все pod DaemonSet riid станут Ready (опрос каждые RIID_WAIT_INTERVAL секунд).
wait-riid-ready:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	deadline=$$(( $$(date +%s) + $(RIID_WAIT_TIMEOUT) )); \
	while :; do \
	  desired=$$(kubectl -n riid-system get daemonset riid -o jsonpath='{.status.desiredNumberScheduled}' 2>/dev/null || echo 0); \
	  ready=$$(kubectl -n riid-system get daemonset riid -o jsonpath='{.status.numberReady}' 2>/dev/null || echo 0); \
	  desired=$${desired:-0}; ready=$${ready:-0}; \
	  echo "wait-riid-ready: ready=$$ready/$$desired"; \
	  if [ "$$desired" -gt 0 ] && [ "$$ready" -eq "$$desired" ]; then \
	    echo "wait-riid-ready: RIID DaemonSet Ready"; \
	    break; \
	  fi; \
	  if [ $$(date +%s) -ge "$$deadline" ]; then \
	    echo "wait-riid-ready: timeout after $(RIID_WAIT_TIMEOUT)s" >&2; \
	    kubectl -n riid-system get pods -l app.kubernetes.io/name=riid -o wide >&2 || true; \
	    exit 1; \
	  fi; \
	  sleep $(RIID_WAIT_INTERVAL); \
	done

# Проверка, что образ контейнера riid уже содержит инструменты (без apt в кластере).
verify-riid-pod-tooling:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	pods="$$(kubectl -n riid-system get pods -l app.kubernetes.io/name=riid -o name)"; \
	[ -n "$$pods" ] || { echo "verify-riid-pod-tooling: no riid pods"; exit 1; }; \
	printf '%s\n' "$$pods" | while IFS= read -r pod; do \
	  echo ">>> $$pod"; \
	  kubectl -n riid-system exec -c riid "$$pod" -- sh -c \
	    'podman --version >/dev/null && test -x /usr/bin/fuse-overlayfs \
	      && grep -q fuse-overlayfs /etc/containers/storage.conf \
	      && command -v curl >/dev/null && curl --version | head -n1 \
	      && command -v jq >/dev/null && jq --version'; \
	done; \
	echo "verify-riid-pod-tooling: ok"

# vmagent (DaemonSet) для сбора метрик по prometheus-аннотациям pod/service.
install-metrics-collector:
	@command -v yq >/dev/null || { echo "yq not found"; exit 1; }
	@[ -f "$(INFRA_VERSIONS_FILE)" ] || { echo "infra versions not found: $(INFRA_VERSIONS_FILE)"; exit 1; }
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	VMAGENT_IMAGE=$$(yq e '.monitoring.vmagent.image' "$(INFRA_VERSIONS_FILE)"); \
	[ "$$VMAGENT_IMAGE" != "null" ] && [ -n "$$VMAGENT_IMAGE" ] || { echo "monitoring.vmagent.image is empty in $(INFRA_VERSIONS_FILE)"; exit 1; }; \
	kubectl apply -f "$(K8S)/monitoring/worker/vmagent-rbac.yaml"; \
	kubectl apply -f "$(K8S)/monitoring/worker/vmagent-configmap.yaml"; \
	kubectl apply -f "$(K8S)/monitoring/worker/vmagent-daemonset.yaml"; \
	kubectl -n riid-system set image daemonset/vmagent vmagent="$$VMAGENT_IMAGE"

wait-metrics-collector:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	kubectl -n riid-system rollout status daemonset/vmagent

# ConfigMap `grafana-dashboards-json` и `grafana-home-dashboard` из config/metrics/grafana/ (kubectl apply; не часть Helm).
sync-monitoring-observer-chart:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	bash "$(OBSERVER_CHART)/apply-grafana-configmaps-from-repo.sh"; \
	if kubectl -n riid-system get deploy/grafana >/dev/null 2>&1; then \
	  kubectl rollout restart deployment/grafana -n riid-system; \
	  kubectl rollout status deployment/grafana -n riid-system --timeout=180s; \
	else \
	  echo "sync-monitoring-observer-chart: deploy/grafana not in riid-system (restart skipped)." >&2; \
	fi

# VictoriaMetrics + Grafana + kube-state-metrics на monitoring-ноде (Helm). При первом переходе с kubectl apply: helm uninstall riid-observer -n riid-system.
install-monitoring-observer: sync-monitoring-observer-chart
	@command -v helm >/dev/null || { echo "helm not found (same install as Dragonfly)"; exit 1; }
	@command -v yq >/dev/null || { echo "yq not found"; exit 1; }
	@[ -f "$(INFRA_VERSIONS_FILE)" ] || { echo "infra versions not found: $(INFRA_VERSIONS_FILE)"; exit 1; }
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	VM_IMAGE=$$(yq e '.monitoring.victoria_metrics.image' "$(INFRA_VERSIONS_FILE)"); \
	GRAFANA_IMAGE=$$(yq e '.monitoring.grafana.image' "$(INFRA_VERSIONS_FILE)"); \
	KSM_IMAGE=$$(yq e '.monitoring.kube_state_metrics.image' "$(INFRA_VERSIONS_FILE)"); \
	[ "$$VM_IMAGE" != "null" ] && [ -n "$$VM_IMAGE" ] || { echo "monitoring.victoria_metrics.image is empty in $(INFRA_VERSIONS_FILE)"; exit 1; }; \
	[ "$$GRAFANA_IMAGE" != "null" ] && [ -n "$$GRAFANA_IMAGE" ] || { echo "monitoring.grafana.image is empty in $(INFRA_VERSIONS_FILE)"; exit 1; }; \
	[ "$$KSM_IMAGE" != "null" ] && [ -n "$$KSM_IMAGE" ] || { echo "monitoring.kube_state_metrics.image is empty in $(INFRA_VERSIONS_FILE)"; exit 1; }; \
	helm upgrade --install riid-observer "$(OBSERVER_CHART)" \
		--namespace riid-system \
		--set "victoriaMetrics.image=$$VM_IMAGE" \
		--set "grafana.image=$$GRAFANA_IMAGE" \
		--set "kubeStateMetrics.image=$$KSM_IMAGE" \
		--wait=false && \
	kubectl rollout restart deployment/grafana -n riid-system

monitor-proxy:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	kubectl port-forward -n riid-system svc/grafana 3000:3000

wait-monitoring-observer:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl port-forward -n riid-system svc/grafana 3000:3000

# POST /pull в первом pod RIID (curl уже в образе контейнера). Другая нода: NODE=имя-ноды make smoke-download
# smoke: SMOKE_REPOSITORY — имя как на Docker Hub (default library/jobber). Для Selectel зеркала:
#   из bootstrap/registry/Makefile: init-performance-registry-images (пишет registry/performance-registry-smoke-map.tsv), затем SMOKE_REGISTRY_TARGET=selectel
SMOKE_REPOSITORY ?= library/jobber
SMOKE_REGISTRY_TARGET ?= dockerhub
SMOKE_REPOSITORY_MAP_FILE ?=
SMOKE_REFERENCE ?= latest
RUNTIME_ID ?= podman
smoke-download:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	REPO=$$(SMOKE_REGISTRY_TARGET="$(SMOKE_REGISTRY_TARGET)" \
	  SMOKE_REPOSITORY_MAP_FILE="$(SMOKE_REPOSITORY_MAP_FILE)" \
	  bash "$(SELECTEL_DIR)/registry/smoke-resolve-repository.sh" "$(SMOKE_REPOSITORY)"); \
	if [ -n "$(NODE)" ]; then \
	  POD=$$(kubectl -n riid-system get pods -l 'app.kubernetes.io/name=riid' --field-selector=spec.nodeName=$(NODE) -o jsonpath='{.items[0].metadata.name}'); \
	else \
	  POD=$$(kubectl -n riid-system get pods -l 'app.kubernetes.io/name=riid' -o jsonpath='{.items[0].metadata.name}'); \
	fi; \
	[ -n "$$POD" ] || { echo "smoke-download: no RIID pod (install-riid / worker-image образ?)" >&2; exit 1; }; \
	kubectl -n riid-system exec -c riid "$$POD" -- \
	  curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
	  -H 'Content-Type: application/json' \
	  -d "{\"repository\":\"$$REPO\",\"reference\":\"$(SMOKE_REFERENCE)\",\"runtimeId\":\"$(RUNTIME_ID)\"}"

# POST /pull во всех pod RIID (по одному на ноду); curl должен быть в образе контейнера.
smoke-download-all:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	REPO=$$(SMOKE_REGISTRY_TARGET="$(SMOKE_REGISTRY_TARGET)" \
	  SMOKE_REPOSITORY_MAP_FILE="$(SMOKE_REPOSITORY_MAP_FILE)" \
	  bash "$(SELECTEL_DIR)/registry/smoke-resolve-repository.sh" "$(SMOKE_REPOSITORY)"); \
	SMOKE_REPOSITORY="$$REPO" SMOKE_REFERENCE="$(SMOKE_REFERENCE)" RUNTIME_ID="$(RUNTIME_ID)" \
	  bash "$(SELECTEL_DIR)/scripts/smoke-download-all-riid-pods.sh"

# Selectech: default StorageClass → Dragonfly Helm → RIID (подряд; не параллелить с -j).
install-all:
	@$(MAKE) -C "$(SELECTEL_DIR)" storage-default
	@$(MAKE) -C "$(SELECTEL_DIR)" validate-cluster-state
	@$(MAKE) -C "$(SELECTEL_DIR)" mark-monitoring-node-auto
	@$(MAKE) -C "$(SELECTEL_DIR)" mark-registry-node-auto
	@$(MAKE) -C "$(SELECTEL_DIR)" install-dragonfly
	@$(MAKE) -C "$(SELECTEL_DIR)" install-riid
	@$(MAKE) -C "$(SELECTEL_DIR)" wait-riid-ready
	@$(MAKE) -C "$(SELECTEL_DIR)" verify-riid-pod-tooling
	@$(MAKE) -C "$(SELECTEL_DIR)" install-metrics-collector
	@$(MAKE) -C "$(SELECTEL_DIR)" wait-metrics-collector
	@$(MAKE) -C "$(SELECTEL_DIR)" install-monitoring-observer
	@$(MAKE) -C "$(SELECTEL_DIR)" wait-monitoring-observer
# Local registry, dataset, registry-профиль и зеркалирование образов — bootstrap/registry/Makefile.

print-pod:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	POD=$$(kubectl -n riid-system get pods -l app.kubernetes.io/name=riid -o jsonpath='{.items[0].metadata.name}'); \
	DF_POD=$$(kubectl -n dragonfly-system get pods -l app=dragonfly,component=client -o jsonpath='{.items[0].metadata.name}'); \
	echo "POD=$$POD"; \
	echo "DF_POD=$$DF_POD"

# Берёт image из deploy/k8s/riid/daemonset.yaml и ставит его на daemonset/riid.
rollout-riid-image-from-manifest:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	IMAGE=$$(awk '\
	  /^[[:space:]]*-[[:space:]]*name:[[:space:]]*riid[[:space:]]*$$/ { in_riid=1; next } \
	  in_riid && /^[[:space:]]*image:[[:space:]]*/ { sub(/^[[:space:]]*image:[[:space:]]*/, "", $$0); print; exit } \
	' "$(K8S)/riid/daemonset.yaml"); \
	[ -n "$$IMAGE" ] || { echo "cannot read image from $(K8S)/riid/daemonset.yaml"; exit 1; }; \
	echo "rollout-riid-image-from-manifest: image=$$IMAGE"; \
	kubectl -n riid-system set image daemonset/riid riid="$$IMAGE"; \
	kubectl -n riid-system rollout status daemonset/riid; \
	kubectl -n riid-system get pods -l app.kubernetes.io/name=riid \
	  -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.containerStatuses[0].imageID}{"\n"}{end}'; \
	$(MAKE) -C "$(SELECTEL_DIR)" verify-riid-pod-tooling

