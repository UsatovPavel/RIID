SHELL := /bin/bash
.PHONY: docker-build docker-test dragonfly-single dragonfly-stop dragonfly-multi dragonfly-multi-stop dragonfly-cluster-single dragonfly-cluster-single-stop testing_prompt moduled-test-out integration-test-out quality-check-out victoriametrics victoriametrics-stop vmagent vmagent-d grafana metrics-stack-up metrics-stack-down stack-up download_to_daemon download_to_daemon_10MB download_to_daemon_50MB download_to_daemon_150MB grafana_demo_load

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

# When need to build and fix some spectific test docs
testing_prompt:
	cat internalDocs/prompts/riid-gradle-testing.md > out.txt

# Optional: config/.env with DOCKERHUB_USER + DOCKERHUB_TOKEN (PAT) — same idea as podman login; not committed — .gitignore
# RIID does not read env for registry auth by itself; we pass CLI flags when both vars are set after sourcing .env.
# -Driid.dev.dirtyRegistryLogs=true: full PAT/tokens in logs (local dev only).
DEV_REGISTRY_LOGS := -Driid.dev.dirtyRegistryLogs=true
daemon:
	set -a; [ -f config/.env ] && . ./config/.env; set +a; \
	if [ -n "$$DOCKERHUB_USER" ] && [ -n "$$DOCKERHUB_TOKEN" ]; then \
	  java $(DEV_REGISTRY_LOGS) -jar build/libs/riid.jar --daemon --config ./config/config.yaml \
	    --username "$$DOCKERHUB_USER" --password-env DOCKERHUB_TOKEN; \
	else \
	  java $(DEV_REGISTRY_LOGS) -jar build/libs/riid.jar --daemon --config ./config/config.yaml; \
	fi
# Single-node VictoriaMetrics (Docker). Prometheus remote_write + query API: http://127.0.0.1:8428. In medium cluster one in claster.
# Run before: make vmagent (same host). Stop: make victoriametrics-stop
victoria-metrics:
	docker run -d --name victoria-metrics -p 8428:8428 victoriametrics/victoria-metrics:latest

victoria-metrics_stop:
	docker rm -f victoria-metrics 2>/dev/null || true

# Same as vmagent but detached (for metrics-stack-up). Stop/remove: docker rm -f vmagent
# host.docker.internal + host-gateway: reach RIID and VictoriaMetrics on the WSL/host
# (--network host would scrape Docker VM loopback, not the daemon on WSL).
vmagent-d:
	docker run -d --name vmagent \
		--add-host=host.docker.internal:host-gateway \
		-v "$(CURDIR)/config/metrics:/etc/vmagent:ro" \
		victoriametrics/vmagent:latest \
		-promscrape.config=/etc/vmagent/vmagent-scrape.yaml \
		-remoteWrite.url=http://host.docker.internal:8428/api/v1/write

# VictoriaMetrics + Grafana + vmagent (background). Then run RIID: `make daemon` in another terminal.
# If docker run fails (name already in use): `make metrics-stack-down` and retry.
metrics-stack-create: metrics-stack-down victoria-metrics grafana vmagent-d
	@echo "Grafana: host port 3000 — http://127.0.0.1:3000 (default login admin/admin on first setup)"

# те же метрики, но json подставить новые
metrics-stack-update:
	docker restart grafana

metrics-stack-down:
	docker rm -f vmagent grafana victoria-metrics 2>/dev/null || true

# Grafana: VictoriaMetrics on host — datasource uses host.docker.internal:8428; --add-host gives Linux Docker the same hostname (Docker Desktop already resolves it).
# Default home dashboard: JSON mount + GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH (see config/metrics/grafana/home/riid-home.json).
grafana:
	docker run -d --name grafana -p 3000:3000 \
		--add-host=host.docker.internal:host-gateway \
		-e GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH=/etc/grafana/home-dashboard/riid-home.json \
		-v "$(CURDIR)/config/metrics/grafana/provisioning:/etc/grafana/provisioning:ro" \
		-v "$(CURDIR)/config/metrics/grafana/dashboards:/etc/grafana/dashboards:ro" \
		-v "$(CURDIR)/config/metrics/grafana/home:/etc/grafana/home-dashboard:ro" \
		grafana/grafana-oss:latest

download_to_daemon_1MB:
	curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
  	-H 'Content-Type: application/json' \
  	-d '{"repository":"library/busybox","reference":"latest","runtimeId":"podman"}'

# Official library/jobber (~layers incl. ~10 MiB);
download_to_daemon_10MB:
	curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
  	-H 'Content-Type: application/json' \
  	-d '{"repository":"library/jobber","reference":"latest","runtimeId":"podman"}'

# Official https://hub.docker.com/_/irssi (~50 of MiB; size label approximate)
download_to_daemon_50MB:
	curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
  	-H 'Content-Type: application/json' \
  	-d '{"repository":"library/irssi","reference":"latest","runtimeId":"podman"}'

download_to_daemon_150MB:
	curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
  	-H 'Content-Type: application/json' \
  	-d '{"repository":"library/postgres","reference":"latest","runtimeId":"podman"}'

# Deliberate 4xx for metrics/tests: 422 unknown_runtime (not in daemon --runtime list).
download_to_daemon_error:
	curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
		-H 'Content-Type: application/json' \
		-d '{"repository":"library/busybox","reference":"latest","runtimeId":"__no_such_runtime__"}'

# Deliberate 5xx (HTTP 500 pull_failed): start daemon with RIID_DAEMON_INTERNAL_ERROR_PROBE=1 in the environment,
# then POST with repository __riid_daemon_internal_error_probe__ (see PullHttpHandler).
download_to_daemon_5xx_probe:
	curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
		-H 'Content-Type: application/json' \
		-d '{"repository":"__riid_daemon_internal_error_probe__","reference":"latest","runtimeId":"podman"}'

# Grafana demo: each round = library/jobber -> sleep 15s -> library/busybox -> sleep 30s (10 rounds). Needs `make daemon` + metrics stack.
grafana_demo_load:
	@set -e; \
	echo "=== grafana_demo_load: 10 rounds: jobber, 15s, busybox, 30s ==="; \
	for i in $$(seq 1 10); do \
	  echo "[round] $$i/10 $$(date -Iseconds) jobber"; \
	  curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
	    -H 'Content-Type: application/json' \
	    -d '{"repository":"library/jobber","reference":"latest","runtimeId":"podman"}' \
	    | (command -v jq >/dev/null 2>&1 && jq -c . || cat); echo; \
	  sleep 15; \
	  echo "[round] $$i/10 $$(date -Iseconds) busybox"; \
	  curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
	    -H 'Content-Type: application/json' \
	    -d '{"repository":"library/busybox","reference":"latest","runtimeId":"podman"}' \
	    | (command -v jq >/dev/null 2>&1 && jq -c . || cat); echo; \
	  [ $$i -lt 10 ] && sleep 30; \
	done; \
	echo "=== grafana_demo_load done $$(date -Iseconds) ==="