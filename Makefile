SHELL := /bin/bash
.PHONY: docker-build docker-test dragonfly-single dragonfly-stop dragonfly-multi dragonfly-multi-stop dragonfly-cluster-single dragonfly-cluster-single-stop testing_prompt moduled-test-out integration-test-out quality-check-out

# clean build artifacts(for dev): Eclipse, Dragonfly, CIFuzz, VSCode
clean-dirs:
	rm -rf bin  
	rm -rf .dragonfly
	rm -rf .cifuzz-corpus
	rm -rf .vscode

# Minikube/Helm: логи Dragonfly (после ./minikube-dragonfly.sh)
minikube-dragonfly-logs:
	@echo "=== Dragonfly client (dfdaemon) ===" > out.txt
	@minikube kubectl -- logs -n dragonfly-system -l app=dragonfly,component=client --tail=100 2>/dev/null >> out.txt || kubectl logs -n dragonfly-system -l app=dragonfly,component=client --tail=100 >> out.txt
	@echo "" >> out.txt
	@echo "=== Dragonfly manager ===" >> out.txt
	@minikube kubectl -- logs -n dragonfly-system -l app=dragonfly,component=manager --tail=50 2>/dev/null >> out.txt || kubectl logs -n dragonfly-system -l app=dragonfly,component=manager --tail=50 >> out.txt
	@echo "" >> out.txt
	@echo "=== Dragonfly scheduler ===" >> out.txt
	@minikube kubectl -- logs -n dragonfly-system -l app=dragonfly,component=scheduler --tail=50 2>/dev/null >> out.txt || kubectl logs -n dragonfly-system -l app=dragonfly,component=scheduler --tail=50 >> out.txt
	@echo "Logs written to out.txt"

dragonfly-logs-minikube-client:
	@minikube kubectl -- logs -n dragonfly-system -l app=dragonfly,component=client -f --tail=50 2>/dev/null || kubectl logs -n dragonfly-system -l app=dragonfly,component=client -f --tail=50


dragonfly-logs-minikube-restart:
	@minikube kubectl -- delete pod -n dragonfly-system -l app=dragonfly,component=client 2>/dev/null || kubectl delete pod -n dragonfly-system -l app=dragonfly,component=client
	@echo "Client pod restarted, logs will be fresh"

.PHONY: dragonfly-logs-single dragonfly-logs-single-full dragonfly-logs-manager dragonfly-logs-scheduler dragonfly-logs-daemon1 dragonfly-logs-daemon2 dragonfly-logs-daemon3 dragonfly-logs-minikube dragonfly-logs-minikube-client dragonfly-logs-clear dragonfly-logs-minikube-restart
# Dragonfly integration test (1 node, unix socket)
# Requires: ./scripts/minikube-dragonfly.sh (1 node) or make -C scripts minikube-delete-and-start
dragonfly-integration-test:
	@test -S /var/run/dragonfly/dfdaemon.sock || (echo "Run ./scripts/minikube-dragonfly.sh first"; exit 1)
	@sudo mkdir -p /var/run/dragonfly/output && sudo chmod 777 /var/run/dragonfly/output
	@DFDAEMON_ADDR=unix:///var/run/dragonfly/dfdaemon.sock \
		./gradlew integrationTest -PincludeLocal --tests DragonflySingleP2PExecutorTest

# Dragonfly integration test (2 nodes, port-forward к dfdaemon pod)
# Requires: make -C scripts minikube-2nodes
dragonfly-integration-test-2nodes:
	@POD=$$(kubectl get pods -n dragonfly-system -l app=dragonfly,component=client -o jsonpath='{.items[0].metadata.name}' 2>/dev/null); \
	test -n "$$POD" || (echo "Run make -C scripts minikube-2nodes first"; exit 1); \
	kubectl port-forward -n dragonfly-system "$$POD" 65001:65001 & \
		PF_PID=$$!; \
		trap "kill $$PF_PID 2>/dev/null || true" EXIT; \
		sleep 2; \
		DFDAEMON_ADDR=127.0.0.1:65001 DFDAEMON_OUTPUT_DIR=/tmp/riid-output \
		./gradlew --no-daemon integrationTest -PincludeLocal --tests DragonflySingleP2PExecutorTest


moduled-execute-specific-test:
	@test -n "$(TEST)" || (echo "Usage: make moduled-execute-specific-test TEST=riid.p2p.DragonflyGrpcP2PExecutorTest"; exit 1)
	./gradlew moduledTest -PincludeLocal --tests "$(TEST)"

# Локальная отладка тестов
testing_prompt:
	cat internalDocs/prompts/riid-gradle-testing.md > out.txt