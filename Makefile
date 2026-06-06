SHELL := /bin/bash
.PHONY: docker-build docker-test dragonfly-single dragonfly-stop dragonfly-multi dragonfly-multi-stop dragonfly-cluster-single dragonfly-cluster-single-stop testing_prompt moduled-test-out integration-test-out quality-check-out victoriametrics victoriametrics-stop vmagent vmagent-d grafana metrics-stack-create metrics-stack-update metrics-stack-down stack-up daemon daemon-new daemon-profile daemon-jfr download_bench_daemon download_bench_podman download_bench_podman_warm download_bench_podman_cold_root download_to_daemon download_to_daemon_1MB download_to_daemon_10MB  download_to_daemon_50MB download_to_podman_50MB download_to_podman_50MB_warm download_to_podman_50MB_cold_root download_to_daemon_150MB grafana_demo_load bench_podman_4_pulls_seq bench_riid_4_pulls_seq perf_scenario_a shapki shapki_unpack

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
daemon-new:
	./gradlew clean shadowJar
	$(MAKE) daemon

daemon:
	set -a; [ -f config/.env ] && . ./config/.env; set +a; \
	if [ -n "$$DOCKERHUB_USER" ] && [ -n "$$DOCKERHUB_TOKEN" ]; then \
	  java $(DEV_REGISTRY_LOGS) -jar build/libs/riid.jar --daemon --config ./config/config.yaml \
	    --username "$$DOCKERHUB_USER" --password-env DOCKERHUB_TOKEN; \
	else \
	  java $(DEV_REGISTRY_LOGS) -jar build/libs/riid.jar --daemon --config ./config/config.yaml; \
	fi

daemon-kill:
	pkill -f '[r]iid.jar.*--daemon'
# Single-node VictoriaMetrics (Docker). Prometheus remote_write + query API: http://127.0.0.1:8428. In medium cluster one in claster.
# Run before: make vmagent (same host). Stop: make victoriametrics-stop
victoria-metrics:
	docker run -d --name victoria-metrics -p 8428:8428 victoriametrics/victoria-metrics:latest

victoria-metrics_stop:
	docker rm -f victoria-metrics 2>/dev/null || true

# Same as vmagent but detached (for metrics-stack-create). Stop/remove: docker rm -f vmagent
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
	time curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
  	-H 'Content-Type: application/json' \
  	-d '{"repository":"library/irssi","reference":"latest","runtimeId":"podman"}'

# PR15 scenario (a): library/irssi, 5× холодный кэш (RIID + podman), в stdout — списки ms и riid_median_pull_ms / podman_median_pull_ms.
# Нужны: демон на UDS из config, curl, podman; опционально config/.env и RIID_PERF_CACHE_DIR (см. DaemonBenchmarkColdCachePullsTest).
perf_scenario_a:
	set -a; [ -f config/.env ] && . ./config/.env; set +a; \
	./gradlew performanceTest --tests riid.performance.sequentially.oneimage.DaemonBenchmarkColdCachePullsTest

# Четыре последовательных pull в отдельном store (PopularDockerImagesSizes.txt ~7–17 MiB).
# Перед rm — podman system reset, иначе overlay от root и Permission denied на rm -rf.
bench_podman_4_pulls_seq:
	@echo "=== Podman (дефолтный store): все контейнеры и образы ==="
	-podman rm -af 2>/dev/null || true
	-imgs=$$(podman images -aq); if [ -n "$$imgs" ]; then podman rmi -af $$imgs || true; fi
	@if [ -d /tmp/riid-podman-bench/root ]; then \
		podman --root /tmp/riid-podman-bench/root --runroot /tmp/riid-podman-bench/runroot system reset -f || true; \
	fi
	rm -rf /tmp/riid-podman-bench || podman unshare rm -rf /tmp/riid-podman-bench
	mkdir -p /tmp/riid-podman-bench/root /tmp/riid-podman-bench/runroot
	R="--root /tmp/riid-podman-bench/root --runroot /tmp/riid-podman-bench/runroot"; \
	time podman $$R pull docker.io/library/cirros:latest && \
	time podman $$R pull docker.io/library/jobber:latest && \
	time podman $$R pull docker.io/library/photon:latest && \
	time podman $$R pull docker.io/library/api-firewall:latest

# Те же 4 образа через RIID daemon: холодный TempFileCacheAdapter (pkill + rm riid-cache-tmp-*),
# подъём daemon в фоне, прогрев — один pull library/irssi (~50 MiB) до ответа, затем 4× POST /pull с паузой 10 с.
RIID_SOCK_BENCH ?= /tmp/riid.sock
bench_riid_4_pulls_seq:
	podman rmi -a
	@echo "=== Остановка riid daemon, очистка кэша /tmp/riid-cache-tmp-*, удаление образов в podman ==="
	-pkill -f '[r]iid.jar.*--daemon' || true
	@sleep 2
	rm -rf /tmp/riid-cache-tmp-* 2>/dev/null || true
	@test -f build/libs/riid.jar || (echo "Сначала: ./gradlew shadowJar"; exit 1)
	@echo "=== Запуск daemon в фоне (лог: /tmp/riid-daemon-bench.log) ==="
	@(set -a; [ -f config/.env ] && . ./config/.env; set +a; \
	if [ -n "$$DOCKERHUB_USER" ] && [ -n "$$DOCKERHUB_TOKEN" ]; then \
	  nohup java $(DEV_REGISTRY_LOGS) -jar build/libs/riid.jar --daemon --config ./config/config.yaml \
	    --username "$$DOCKERHUB_USER" --password-env DOCKERHUB_TOKEN \
	    >> /tmp/riid-daemon-bench.log 2>&1 & \
	else \
	  nohup java $(DEV_REGISTRY_LOGS) -jar build/libs/riid.jar --daemon --config ./config/config.yaml \
	    >> /tmp/riid-daemon-bench.log 2>&1 & \
	fi; \
	echo $$! > /tmp/riid-daemon-bench.pid)
	@echo "=== Ожидание UDS $(RIID_SOCK_BENCH) (до 45 с) ==="
	@ok=0; for i in $$(seq 1 45); do [ -S $(RIID_SOCK_BENCH) ] && ok=1 && break; sleep 1; done; \
	if [ "$$ok" != 1 ]; then echo "Таймаут. См. /tmp/riid-daemon-bench.log"; exit 1; fi
	@echo "=== Прогрев: один pull library/irssi (~50 MiB), ждём ответа daemon ==="
	$(MAKE) download_to_daemon_150MB
	$(MAKE) download_to_daemon_50MB
	$(MAKE) download_to_daemon_50MB
	$(MAKE) download_to_daemon_50MB
	echo
	@SC="$(RIID_SOCK_BENCH)"; \
	time curl --unix-socket $$SC -sS -X POST "http://localhost/pull" \
	  -H 'Content-Type: application/json' \
	  -d '{"repository":"library/cirros","reference":"latest","runtimeId":"podman"}'; \
	echo; \
	time curl --unix-socket $$SC -sS -X POST "http://localhost/pull" \
	  -H 'Content-Type: application/json' \
	  -d '{"repository":"library/jobber","reference":"latest","runtimeId":"podman"}'; \
	echo; \
	time curl --unix-socket $$SC -sS -X POST "http://localhost/pull" \
	  -H 'Content-Type: application/json' \
	  -d '{"repository":"library/photon","reference":"latest","runtimeId":"podman"}'; \
	echo; \
	time curl --unix-socket $$SC -sS -X POST "http://localhost/pull" \
	  -H 'Content-Type: application/json' \
	  -d '{"repository":"library/api-firewall","reference":"latest","runtimeId":"podman"}'; \
	echo

# CPU 30 с → ASPROF_OUT; удобный алиас (раньше в .PHONY был только daemon-profile без рецепта → «Nothing to be done»).
ASPROF_OUT ?= /tmp/riid-cpu.html
daemon-profile: daemon-profile_40s

daemon-profile_40s:
	@pid=$$(pgrep -f '[r]iid\.jar.*--daemon' | head -n1); \
	if [ -z "$$pid" ]; then echo "Нет процесса: riid.jar --daemon"; exit 1; fi; \
	echo "PID=$$pid"; \
	pgrep -af 'riid.jar.*--daemon' || true; \
	asprof -e wall -d 40 -f $(ASPROF_OUT) $$pid; \
	echo "Готово: $(ASPROF_OUT)"

# JFR на daemon: jcmd из того же JDK; пока sleep — нагрузка; .jfr открыть в JMC.
JFR_OUT ?= $(CURDIR)/mem/riid.jfr
JFR_SEC ?= 60
daemon-jfr:
	@pid=$$(pgrep -f '[r]iid\.jar.*--daemon' | head -n1); \
	test -n "$$pid" || { echo "Нет riid.jar --daemon"; exit 1; }; \
	f='$(abspath $(JFR_OUT))'; mkdir -p "$$(dirname "$$f")"; \
	jcmd $$pid JFR.start name=riid_$$(date +%s) settings=profile duration=$(JFR_SEC)s filename="$$f"; \
	sleep $(JFR_SEC); \
	ls -la "$$f"

download_to_daemon_150MB:
	curl --unix-socket /tmp/riid.sock -sS -X POST "http://localhost/pull" \
  	-H 'Content-Type: application/json' \
  	-d '{"repository":"library/postgres","reference":"latest","runtimeId":"podman"}'

# Распаковать prefix-сжатый cpool в копии профиля (исходник mem/riid-cpu.html не трогаем).
shapki_unpack:
	@test -f mem/riid-cpu.html || (echo "Нет mem/riid-cpu.html — положите flame HTML в mem/"; exit 1)
	cp mem/riid-cpu.html mem/riid-cpu-unpack-test.html
	node scripts/unpack-flame-cpool.mjs mem/riid-cpu-unpack-test.html

shapki:
	node scripts/flame-self-extract.mjs mem/riid-cpu.html mem/Шапки.md

shapki_wall:
	mv /tmp/riid-cpu.html ./mem/riid-cpu-wall.html
	node scripts/flame-self-extract.mjs mem/riid-cpu-wall.html mem/Шапки-wall.md
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