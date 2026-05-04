# Selectech — kubeconfig: локальный файл (по умолчанию serverConfig.yaml; при смене аренды часто тот же файл, контекст внутри меняется).
CONFIG_FILE ?= serverConfig.yaml
SELECTEL_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
K8S := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/..)
OBSERVER_CHART := $(K8S)/monitoring/helm/riid-observer
KUBECONFIG := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))$(CONFIG_FILE))
LOCAL_REGISTRY_MANIFEST := $(K8S)/performance/registry/local-registry.yaml

# OpenStack Cinder: класс по умолчанию для PVC без storageClass (Helm Dragonfly / Bitnami MySQL+Redis).
# Переопределить: make storage-default STORAGE_CLASS=другой-класс
STORAGE_CLASS ?= fast.ru-3b
RIID_WAIT_INTERVAL ?= 15
RIID_WAIT_TIMEOUT ?= 600
MONITORING_NODE ?=
REGISTRY_NODE ?=
PERF_REGISTRY_DATASET ?= $(K8S)/performance/input/dataset_a.tsv

.PHONY: connect nodes mark-monitoring-node-auto mark-registry-node-auto storage-default install-dragonfly secret-config secret-config-riid registry-dockerhub registry-selectel registry-local registry-apply-profile install-local-registry wait-local-registry load-performance-registry-dataset install-riid wait-riid-ready install-riid-runtimes install-smoke-utils install-metrics-collector wait-metrics-collector sync-monitoring-observer-chart install-monitoring-observer wait-monitoring-observer smoke-download smoke-download-all install-all init-performance-registry-images init-performance-registry-images-state-init init-performance-registry-images-incremental rollout-riid-image-from-manifest

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
install-dragonfly:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	"$(K8S)/dragonfly/install-dragonfly.sh"

# Явно собрать/применить Secret riid-config-secret из $(K8S)/riid/.env (профиль Docker Hub).
secret-config: secret-config-riid

secret-config-riid:
	@$(MAKE) -C "$(SELECTEL_DIR)" registry-apply-profile PROFILE=dockerhub

# Переключить client.registries в кластере (Secret + rollout riid). Нужен $(K8S)/riid/.env с нужными кредами.
# PROFILE=selectel: дополнительно podman login (riid/registry/sync-podman-selectel-login.sh) во все Running-подах riid.
registry-dockerhub:
	@$(MAKE) -C "$(SELECTEL_DIR)" registry-apply-profile PROFILE=dockerhub

registry-selectel:
	@$(MAKE) -C "$(SELECTEL_DIR)" registry-apply-profile PROFILE=selectel

registry-local:
	@$(MAKE) -C "$(SELECTEL_DIR)" registry-apply-profile PROFILE=local

registry-apply-profile:
	@if [ -z "$(PROFILE)" ]; then echo "registry-apply-profile: set PROFILE=dockerhub|selectel|local"; exit 1; fi; \
	export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	[ -f "$(K8S)/riid/.env" ] || { echo "registry-apply-profile: нет $(K8S)/riid/.env (см. riid/.env.example)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	bash "$(K8S)/riid/render-riid-config-secret.sh" --apply --profile "$(PROFILE)"; \
	if kubectl -n riid-system get daemonset riid >/dev/null 2>&1; then \
	  kubectl -n riid-system rollout restart daemonset riid; \
	  kubectl -n riid-system rollout status daemonset riid; \
	else \
	  echo "registry-apply-profile: daemonset/riid not found yet, restart skipped"; \
	fi; \
	if [ "$(PROFILE)" = selectel ]; then \
	  bash "$(K8S)/riid/registry/sync-podman-selectel-login.sh"; \
	fi

# Поднимает local registry для схемы 10 + 1 + 1: отдельный pod на ноде с label riid.registry=true.
install-local-registry:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	kubectl apply -f "$(LOCAL_REGISTRY_MANIFEST)"

wait-local-registry:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	kubectl -n registry-system rollout status deployment/local-registry

# Прогоняет dataset через podman на RIID pod у registry-ноды (riid.registry=true) и пушит в local-registry.
# По умолчанию dataset A: deploy/k8s/performance/input/dataset_a.tsv
load-performance-registry-dataset:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	DATASET_FILE="$(PERF_REGISTRY_DATASET)" \
	  bash "$(K8S)/performance/registry/load-dataset-into-local-registry.sh"

# Работает из любой cwd; манифесты: $(K8S)/…
# Secret riid-config-secret: при наличии $(K8S)/riid/.env — render-riid-config-secret.sh (yq + kubectl), иначе пропуск.
install-riid:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG (override CONFIG_FILE=...)"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	kubectl apply -f "$(K8S)/namespace.yaml"; \
	kubectl apply -f "$(K8S)/riid/configmap.yaml"; \
	if [ -f "$(K8S)/riid/.env" ]; then \
	  $(MAKE) -C "$(SELECTEL_DIR)" secret-config-riid; \
	else \
	  echo "install-riid: нет $(K8S)/riid/.env — пропуск riid-config-secret (см. riid/.env.example)" >&2; \
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

# Установка runtime-инструментов в pod RIID (по аналогии с CI: podman install + проверка docker CLI).
install-riid-runtimes:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	for p in $$(kubectl -n riid-system get pods -l app.kubernetes.io/name=riid -o name); do \
	  echo ">>> $$p"; \
	  kubectl -n riid-system exec -c riid "$$p" -- sh -c 'export DEBIAN_FRONTEND=noninteractive; apt-get install -y --no-install-recommends podman fuse-overlayfs && mkdir -p /etc/containers'; \
	  kubectl cp "$(CURDIR)/../riid/storage.conf" "riid-system/$$(echo "$$p" | cut -d/ -f2):/etc/containers/storage.conf" -c riid; \
	  kubectl -n riid-system exec -c riid "$$p" -- sh -c 'podman --version && test -x /usr/bin/fuse-overlayfs && grep -q fuse-overlayfs /etc/containers/storage.conf'; \
	  kubectl -n riid-system exec -c riid "$$p" -- sh -c 'docker --version || true'; \
	done

# curl в подах RIID (debian-образ) — для smoke POST /pull по Unix-сокету; в каждом pod DaemonSet
install-smoke-utils:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	for p in $$(kubectl -n riid-system get pods -l app.kubernetes.io/name=riid -o name); do \
	  echo ">>> $$p"; \
	  kubectl -n riid-system exec -c riid "$$p" -- sh -c 'export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && apt-get install -y --no-install-recommends curl'; \
	done; \
	echo "Done. Smoke POST /pull: deploy/k8s/internalDocs.md" >&2

# vmagent (DaemonSet) для сбора метрик по prometheus-аннотациям pod/service.
install-metrics-collector:
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	kubectl apply -f "$(K8S)/monitoring/worker/vmagent-rbac.yaml"; \
	kubectl apply -f "$(K8S)/monitoring/worker/vmagent-configmap.yaml"; \
	kubectl apply -f "$(K8S)/monitoring/worker/vmagent-daemonset.yaml"

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
	@export KUBECONFIG="$(KUBECONFIG)"; \
	[ -f "$$KUBECONFIG" ] || { echo "kubeconfig not found: $$KUBECONFIG"; exit 1; }; \
	CTX=$$(kubectl config current-context) || exit 1; \
	kubectl config use-context "$$CTX"; \
	helm upgrade --install riid-observer "$(OBSERVER_CHART)" \
		--namespace riid-system \
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

# POST /pull в первом pod RIID (нужен curl: make install-smoke-utils). Другая нода: NODE=имя-ноды make smoke-download
# smoke: SMOKE_REPOSITORY — имя как на Docker Hub (default library/jobber). Для Selectel зеркала:
#   make init-performance-registry-images (пишет registry/performance-registry-smoke-map.tsv), затем SMOKE_REGISTRY_TARGET=selectel
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
	[ -n "$$POD" ] || { echo "smoke-download: no RIID pod (install-riid + install-smoke-utils?)" >&2; exit 1; }; \
	kubectl -n riid-system exec -c riid "$$POD" -- \
	  curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
	  -H 'Content-Type: application/json' \
	  -d "{\"repository\":\"$$REPO\",\"reference\":\"$(SMOKE_REFERENCE)\",\"runtimeId\":\"$(RUNTIME_ID)\"}"

# POST /pull во всех pod RIID (по одному на ноду). Нужен curl: install-smoke-utils.
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
	@$(MAKE) -C "$(SELECTEL_DIR)" mark-monitoring-node-auto
	@$(MAKE) -C "$(SELECTEL_DIR)" mark-registry-node-auto
	@$(MAKE) -C "$(SELECTEL_DIR)" install-dragonfly
	@$(MAKE) -C "$(SELECTEL_DIR)" install-riid
	@$(MAKE) -C "$(SELECTEL_DIR)" wait-riid-ready 
	@$(MAKE) -C "$(SELECTEL_DIR)" install-metrics-collector
	@$(MAKE) -C "$(SELECTEL_DIR)" wait-metrics-collector
	@$(MAKE) -C "$(SELECTEL_DIR)" install-riid-runtimes
	@$(MAKE) -C "$(SELECTEL_DIR)" install-local-registry
	@$(MAKE) -C "$(SELECTEL_DIR)" install-smoke-utils
	@$(MAKE) -C "$(SELECTEL_DIR)" install-monitoring-observer
	@$(MAKE) -C "$(SELECTEL_DIR)" wait-monitoring-observer
	@$(MAKE) -C "$(SELECTEL_DIR)" wait-local-registry
	@$(MAKE) -C "$(SELECTEL_DIR)" load-performance-registry-dataset
	@$(MAKE) -C "$(SELECTEL_DIR)" registry-local
# comment registry-local if you want DockerHub as registry provider or use registry-selectel
# что-то может не поставится = > ручками шаги доделывать

# Зеркалирование образов: объединение (без дубликатов) deploy/k8s/Selectel/registry/images_list.sizes.tsv и списка library/* из Java (PopularDockerHubImagesFromProgramDocs); см. скрипт.
# Инкремент (только новые строки снизу TSV): один раз make init-performance-registry-images-state-init, затем make init-performance-registry-images-incremental.
# В deploy/k8s/Selectech/.env: REGISTRY_USER, REGISTRY_PASSWORD, REGISTRY (например cr.selcloud.ru/my-registry).
# Другой файл окружения: make init-performance-registry-images ENV_FILE=/path/.env
ENV_FILE ?= $(SELECTEL_DIR)/.env
init-performance-registry-images:
	@"$(SELECTEL_DIR)/registry/init-performance-registry-images.sh" "$(ENV_FILE)"

init-performance-registry-images-state-init:
	@"$(SELECTEL_DIR)/registry/init-performance-registry-images-incremental.sh" --init-state "$(ENV_FILE)"

# Опции: REGISTRY_INCREMENTAL_ARGS='--from-line 104' или --no-update-state
init-performance-registry-images-incremental:
	@"$(SELECTEL_DIR)/registry/init-performance-registry-images-incremental.sh" $(REGISTRY_INCREMENTAL_ARGS) "$(ENV_FILE)"

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
	$(MAKE) -C "$(SELECTEL_DIR)" install-smoke-utils; \
	$(MAKE) -C "$(SELECTEL_DIR)" install-riid-runtimes

