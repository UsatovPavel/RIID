# Minikube + Dragonfly. Требует: ./install.sh (один раз)
# make minikube-delete-and-start — 1 узел (driver=none)
# make minikube-2nodes — 2 узла P2P (driver=docker)

.PHONY: minikube-delete-and-start minikube-2nodes minikube-get-podes _helm-install _helm-install-2nodes _output-dir minikube-2nodes-integration-test

minikube-delete-and-start:
	@echo ">>> Остановка и пересоздание Minikube (1 узел, driver=none)..."
	minikube stop 2>/dev/null || true
	minikube delete 2>/dev/null || true
	sudo minikube start --driver=none
	@if [ -f /root/.kube/config ] && [ "$$(id -u)" != "0" ]; then \
		mkdir -p "$$HOME/.kube"; \
		sudo cp /root/.kube/config "$$HOME/.kube/config"; \
		sudo chown "$$(id -u):$$(id -g)" "$$HOME/.kube/config"; \
	fi
	@minikube status || (echo ">>> Minikube не запустился. Проверьте: cri-dockerd, CNI plugins (install.sh)" >&2; exit 1)
	@KUBECONFIG="$$HOME/.kube/config" $(MAKE) _helm-install
	@$(MAKE) _output-dir

minikube-2nodes:
	@echo ">>> Остановка и пересоздание Minikube (2 узла P2P, driver=docker)..."
	minikube stop 2>/dev/null || true
	minikube delete 2>/dev/null || true
	@echo ">>> Целевое: control-plane 5GB/3CPU, worker 3GB/1CPU. Minikube даёт обоим одинаково"
	minikube start --driver=docker --nodes 2 --memory=4080 --cpus=2
	@minikube status || (echo ">>> Minikube не запустился" >&2; exit 1)
	@mkdir -p /tmp/riid-output && chmod 777 /tmp/riid-output
	@echo ">>> minikube mount (фон) — shared output для RIID. Остановить: pkill -f 'minikube mount'"
	@(minikube mount /tmp/riid-output:/tmp/riid-output &) && sleep 3
	@KUBECONFIG="$$HOME/.kube/config" $(MAKE) _helm-install-2nodes
	@$(MAKE) _output-dir
	@echo ">>> 2 узла готовы. RIID: port-forward + DFDAEMON_ADDR=127.0.0.1:65001 DFDAEMON_OUTPUT_DIR=/tmp/riid-output"

_helm-install:
	@echo ">>> Проверка доступа к кластеру..."
	@kubectl cluster-info >/dev/null 2>&1 || (echo ">>> kubectl не видит кластер. Проверьте KUBECONFIG=$$HOME/.kube/config и что minikube запущен." >&2; exit 1)
	@echo ">>> Добавление Helm-репозитория Dragonfly..."
	helm repo add dragonfly https://dragonflyoss.github.io/helm-charts/ 2>/dev/null || true
	helm repo update
	@echo ">>> Установка Dragonfly..."
	helm uninstall dragonfly -n dragonfly-system 2>/dev/null || true
	helm install --wait --timeout 15m --create-namespace --namespace dragonfly-system dragonfly dragonfly/dragonfly -f values.yaml
	@echo ""
	@echo ">>> Dragonfly установлен. Проверка:"
	minikube kubectl -- get pods -n dragonfly-system

_helm-install-2nodes:
	@echo ">>> Проверка доступа к кластеру..."
	@kubectl cluster-info >/dev/null 2>&1 || (echo ">>> kubectl не видит кластер" >&2; exit 1)
	@echo ">>> Добавление Helm-репозитория Dragonfly..."
	helm repo add dragonfly https://dragonflyoss.github.io/helm-charts/ 2>/dev/null || true
	helm repo update
	@echo ">>> Установка Dragonfly (TCP:65001, /tmp/riid-output)..."
	helm uninstall dragonfly -n dragonfly-system 2>/dev/null || true
	helm install --wait --timeout 15m --create-namespace --namespace dragonfly-system dragonfly dragonfly/dragonfly -f values.yaml -f values-minikube-2nodes.yaml
	@echo ""
	@echo ">>> Dragonfly (2-node) установлен:"
	minikube kubectl -- get pods -n dragonfly-system -o wide

_output-dir:
	sudo mkdir -p /var/run/dragonfly/output
	sudo chmod 777 /var/run/dragonfly/output
	@echo ""
	@echo ">>> RIID подключается к dfdaemon: unix:///var/run/dragonfly/dfdaemon.sock (1 node) или port-forward (2 nodes)"

minikube-get-podes:
	minikube kubectl -- get pods -n dragonfly-system --show-labels

# Интеграционный тест RIID для 2-node (из корня: make dragonfly-integration-test-2nodes)
minikube-2nodes-integration-test:
	@cd .. && $(MAKE) dragonfly-integration-test-2nodes
