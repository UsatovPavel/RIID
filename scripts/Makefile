# Minikube + Dragonfly. Требует: ./install.sh (один раз)
# make minikube-delete-and-start — 1 узел (driver=none)
# make minikube-2nodes — 2 узла P2P (driver=docker)

.PHONY: minikube-delete-and-start minikube-2nodes minikube-get-podes _helm-install _helm-install-2nodes _output-dir minikube-2nodes-integration-test

minikube-delete-and-start:
	@echo ">>> Остановка и пересоздание Minikube (1 узел, driver=none)..."
	minikube stop 2>/dev/null || true
	minikube delete 2>/dev/null || true
	@sudo mkdir -p /etc/cni/net.d
	sudo env HOME="$$HOME" CHANGE_MINIKUBE_NONE_USER=true minikube start --driver=none
	@if [ -f /root/.kube/config ] && [ "$$(id -u)" != "0" ]; then \
		mkdir -p "$$HOME/.kube"; \
		sudo cp /root/.kube/config "$$HOME/.kube/config"; \
		sudo chown "$$(id -u):$$(id -g)" "$$HOME/.kube/config"; \
		[ -d /root/.minikube ] && sudo cp -r /root/.minikube "$$HOME/.minikube" && sudo chown -R "$$(id -u):$$(id -g)" "$$HOME/.minikube" || true; \
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
	@TMP_VALUES=$$(mktemp); \
	trap 'rm -f "$$TMP_VALUES"' EXIT; \
	./render-values-from-infra.sh "$$TMP_VALUES"; \
	echo ">>> Effective images from deploy/k8s/config/infra_versions.yaml:"; \
	yq e '.manager.image, .scheduler.image, .seedClient.image, .client.image' "$$TMP_VALUES"; \
	helm uninstall dragonfly -n dragonfly-system 2>/dev/null || true; \
	helm install --wait --timeout 15m --create-namespace --namespace dragonfly-system dragonfly dragonfly/dragonfly -f "$$TMP_VALUES"
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
	@TMP_VALUES=$$(mktemp); \
	trap 'rm -f "$$TMP_VALUES"' EXIT; \
	./render-values-from-infra.sh "$$TMP_VALUES"; \
	echo ">>> Effective images from deploy/k8s/config/infra_versions.yaml:"; \
	yq e '.manager.image, .scheduler.image, .seedClient.image, .client.image' "$$TMP_VALUES"; \
	helm uninstall dragonfly -n dragonfly-system 2>/dev/null || true; \
	helm install --wait --timeout 15m --create-namespace --namespace dragonfly-system dragonfly dragonfly/dragonfly -f "$$TMP_VALUES" -f values-minikube-2nodes.yaml
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
# Остановить всё
delete_all:
	minikube stop 2>/dev/null || true
	minikube delete --all --purge 2>/dev/null || true
	sudo systemctl stop kubelet 2>/dev/null || true
	sudo systemctl stop k3s 2>/dev/null || true
	sudo systemctl stop k3s-agent 2>/dev/null || true
	sudo systemctl stop cri-dockerd 2>/dev/null || true
	sudo systemctl stop docker 2>/dev/null || true

	# Убить процессы
	sudo pkill -9 kubelet 2>/dev/null || true
	sleep 2

	# Удалить всё
	sudo rm -rf /etc/kubernetes /var/lib/kubelet /var/lib/rancher /etc/rancher
	sudo rm -rf /var/lib/minikube /var/lib/cni /etc/cni
	sudo rm -rf /var/lib/etcd ~/.minikube
	sudo mkdir -p /etc/cni/net.d

	# Запустить Docker
	sudo systemctl start docker

logs-sql:
	kubectl -n dragonfly-system describe pod dragonfly-mysql-0 > ../out.txt
	kubectl -n dragonfly-system logs dragonfly-mysql-0 --all-containers=true >> ../out.txt
	kubectl -n dragonfly-system logs dragonfly-mysql-0 --all-containers=true --previous >> ../out.txt
	kubectl -n dragonfly-system get events --sort-by='.lastTimestamp' | tail -n 80 >> ../out.txt