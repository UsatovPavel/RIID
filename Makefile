SHELL := /bin/bash
.PHONY: docker-build docker-test dragonfly-single dragonfly-stop dragonfly-multi dragonfly-multi-stop dragonfly-cluster-single dragonfly-cluster-single-stop
docker-build:
	docker build -t riid-demo .

docker-test:
	docker build --target builder -t riid-test .
	docker run --rm -v gradle-cache:/root/.gradle riid-test ./gradlew test -PdisableLocal
# prod configuration
dragonfly-single:
	docker rm -f dfdaemon >/dev/null 2>&1 || true
	mkdir -p /tmp/dragonfly-sock
	docker run -d --name dfdaemon --network host --privileged \
		-v /tmp/dragonfly-sock:/var/run \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/dfdaemon:latest \
		--config /etc/dragonfly/dfget-single-sock.yaml

dragonfly-stop:
	docker rm -f dfdaemon >/dev/null 2>&1 || true
#
dragonfly-multi:
	docker network create dragonfly-net >/dev/null 2>&1 || true
	docker rm -f dfmanager dfscheduler dfdaemon1 dfdaemon2 dfdaemon3 dfmysql dfredis >/dev/null 2>&1 || true
	docker run -d --name dfmysql --network dragonfly-net \
		-e MYSQL_ROOT_PASSWORD=root \
		-e MYSQL_DATABASE=dragonfly \
		mysql:8
	@echo "Waiting for dfmysql:3306..."
	@for i in {1..30}; do \
		if docker run --rm --network dragonfly-net busybox sh -c "nc -z dfmysql 3306" >/dev/null 2>&1; then \
			echo "dfmysql is ready"; \
			break; \
		fi; \
		sleep 1; \
	done
	docker run -d --name dfredis --network dragonfly-net \
		redis:7
	docker run -d --name dfmanager --network dragonfly-net \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		-e MANAGER_CONFIG=/etc/dragonfly/manager.yaml \
		dragonflyoss/manager:latest \
		--config /etc/dragonfly/manager.yaml --console
	@echo "Waiting for dfmanager:65003..."
	@for i in {1..30}; do \
		if docker run --rm --network dragonfly-net busybox sh -c "nc -z dfmanager 65003" >/dev/null 2>&1; then \
			echo "dfmanager is ready"; \
			break; \
		fi; \
		sleep 1; \
	done
	docker run -d --name dfscheduler --network dragonfly-net \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/scheduler:latest \
		--config /etc/dragonfly/scheduler.yaml --console
	@mkdir -p /tmp/dragonfly-d1 /tmp/dragonfly-d2 /tmp/dragonfly-d3
	docker run -d --name dfdaemon1 --network dragonfly-net --privileged \
		-p 65001:65001 \
		-v "/tmp:/tmp" \
		-v "/tmp/dragonfly-d1:/var/lib/dragonfly" \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/dfdaemon:latest \
		--config /etc/dragonfly/dfget-multi.yaml
	docker run -d --name dfdaemon2 --network dragonfly-net --privileged \
		-v "/tmp:/tmp" \
		-v "/tmp/dragonfly-d2:/var/lib/dragonfly" \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/dfdaemon:latest \
		--config /etc/dragonfly/dfget-multi.yaml
	docker run -d --name dfdaemon3 --network dragonfly-net --privileged \
		-v "/tmp:/tmp" \
		-v "/tmp/dragonfly-d3:/var/lib/dragonfly" \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/dfdaemon:latest \
		--config /etc/dragonfly/dfget-multi.yaml

dragonfly-multi-stop:
	docker rm -f dfmanager dfscheduler dfdaemon1 dfdaemon2 dfdaemon3 >/dev/null 2>&1 || true
	docker network rm dragonfly-net >/dev/null 2>&1 || true
	@rm -rf /tmp/dragonfly-d1 /tmp/dragonfly-d2 /tmp/dragonfly-d3

# Single-node cluster: MySQL + Redis + Manager + Scheduler + one dfdaemon (socket for RIID)
dragonfly-cluster-single:
	docker network create dragonfly-net >/dev/null 2>&1 || true
	docker rm -f dfdaemon dfmanager dfscheduler dfmysql dfredis >/dev/null 2>&1 || true
	mkdir -p /tmp/dragonfly-sock /tmp/dragonfly-data/output /tmp/dragonfly-cache
	docker run -d --name dfmysql --network dragonfly-net \
		-e MYSQL_ROOT_PASSWORD=root \
		-e MYSQL_DATABASE=dragonfly \
		mysql:8
	@echo "Waiting for dfmysql:3306..."
	@for i in {1..30}; do \
		if docker run --rm --network dragonfly-net busybox sh -c "nc -z dfmysql 3306" >/dev/null 2>&1; then \
			echo "dfmysql is ready"; \
			break; \
		fi; \
		sleep 1; \
	done
	docker run -d --name dfredis --network dragonfly-net redis:7
	docker run -d --name dfmanager --network dragonfly-net \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		-e MANAGER_CONFIG=/etc/dragonfly/manager.yaml \
		dragonflyoss/manager:latest \
		--config /etc/dragonfly/manager.yaml --console
	@echo "Waiting for dfmanager:65003..."
	@for i in {1..30}; do \
		if docker run --rm --network dragonfly-net busybox sh -c "nc -z dfmanager 65003" >/dev/null 2>&1; then \
			echo "dfmanager is ready"; \
			break; \
		fi; \
		sleep 1; \
	done
	docker run -d --name dfscheduler --network dragonfly-net \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/scheduler:latest \
		--config /etc/dragonfly/scheduler.yaml --console
	docker run -d --name dfdaemon --network dragonfly-net --privileged \
		--add-host=host.docker.internal:host-gateway \
		-v /tmp/dragonfly-sock:/var/run \
		-v /tmp/dragonfly-data:/var/lib/dragonfly \
		-v /tmp/dragonfly-cache:/var/cache/dragonfly \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/dfdaemon:latest \
		--config /etc/dragonfly/dfget-cluster-single.yaml
	@echo "Waiting for dfdaemon socket..."
	@for i in {1..15}; do \
		if [ -S /tmp/dragonfly-sock/dfdaemon.sock ]; then \
			chmod 666 /tmp/dragonfly-sock/dfdaemon.sock 2>/dev/null || sudo chmod 666 /tmp/dragonfly-sock/dfdaemon.sock; \
			echo "dfdaemon socket ready"; \
			break; \
		fi; \
		sleep 1; \
	done

dragonfly-cluster-single-stop:
	docker rm -f dfdaemon dfmanager dfscheduler dfmysql dfredis >/dev/null 2>&1 || true
	docker network rm dragonfly-net >/dev/null 2>&1 || true
	rm -rf /tmp/dragonfly-data /tmp/dragonfly-cache

.PHONY: dragonfly-health dragonfly-health-single dragonfly-health-multi dragonfly-health-cluster-single

dragonfly-health-single:
	@echo "=== Single mode: dfdaemon ===" > out.txt
	@docker ps -a --filter "name=^dfdaemon$$" >> out.txt 2>&1
	@echo "" >> out.txt
	@echo "Socket /tmp/dragonfly-sock:" >> out.txt
	@ls -la /tmp/dragonfly-sock/ >> out.txt 2>&1 || true
	@echo "" >> out.txt
	@echo "dfdaemon logs (last 50 lines):" >> out.txt
	@docker exec dfdaemon sh -c "tail -n 50 /var/log/dragonfly/daemon/core.log /var/log/dragonfly/daemon/grpc.log 2>/dev/null || tail -n 50 /var/log/dragonfly/daemon/*.log 2>/dev/null || echo 'no log files'" >> out.txt 2>&1 || docker logs dfdaemon 2>&1 | tail -n 50 >> out.txt

dragonfly-health-multi:
	@echo "=== Multi mode: dfmanager, dfscheduler, dfdaemon1 ===" > out.txt
	@docker ps --filter "name=df" >> out.txt 2>&1
	@echo "" >> out.txt
	@echo "dfmanager logs:" >> out.txt
	@docker logs dfmanager 2>&1 | head -n 50 >> out.txt
	@echo "" >> out.txt
	@echo "dfscheduler logs:" >> out.txt
	@docker logs dfscheduler 2>&1 | head -n 50 >> out.txt
	@echo "" >> out.txt
	@echo "dfdaemon1 logs:" >> out.txt
	@docker exec dfdaemon1 sh -c "tail -n 30 /var/log/dragonfly/daemon/core.log /var/log/dragonfly/daemon/grpc.log 2>/dev/null" >> out.txt 2>&1 || true

dragonfly-health-cluster-single:
	@echo "=== Cluster single: dfmysql, dfredis, dfmanager, dfscheduler, dfdaemon ===" > out.txt
	@docker ps --filter "name=df" >> out.txt 2>&1
	@echo "" >> out.txt
	@echo "Socket /tmp/dragonfly-sock:" >> out.txt
	@ls -la /tmp/dragonfly-sock/ >> out.txt 2>&1 || true
	@echo "" >> out.txt
	@echo "dfdaemon logs:" >> out.txt
	@docker exec dfdaemon sh -c "tail -n 30 /var/log/dragonfly/daemon/core.log /var/log/dragonfly/daemon/grpc.log 2>/dev/null" >> out.txt 2>&1 || docker logs dfdaemon 2>&1 | tail -n 30 >> out.txt

# Minikube/Helm: логи Dragonfly (после ./minikube-dragonfly.sh)
dragonfly-logs-minikube:
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
dragonfly-logs-single:
	@docker exec dfdaemon sh -c "tail -n 50 /var/log/dragonfly/daemon/stdout.log /var/log/dragonfly/daemon/stderr.log /var/log/dragonfly/daemon/core.log /var/log/dragonfly/daemon/grpc.log 2>/dev/null" > out.txt 2>&1 || docker logs dfdaemon 2>&1 | tail -n 50 > out.txt

dragonfly-logs-single-full:
	@echo "=== dfdaemon log dir ===" > out.txt
	@docker exec dfdaemon sh -c "ls -la /var/log/dragonfly/daemon/" >> out.txt 2>&1
	@echo "" >> out.txt
	@docker exec dfdaemon sh -c "for f in /var/log/dragonfly/daemon/*.log; do echo \"===> \$$f <===\"; tail -n 100 \$$f 2>/dev/null; echo; done" >> out.txt 2>&1

dragonfly-logs-manager:
	@docker logs dfmanager 2>&1 | tail -n 50 > out.txt

dragonfly-logs-scheduler:
	@docker logs dfscheduler 2>&1 | tail -n 50 > outSheduler.txt

dragonfly-logs-daemon1:
	@docker exec dfdaemon1 sh -c "tail -n 50 /var/log/dragonfly/daemon/stdout.log /var/log/dragonfly/daemon/stderr.log /var/log/dragonfly/daemon/core.log /var/log/dragonfly/daemon/grpc.log" > out.txt

dragonfly-logs-daemon2:
	@docker exec dfdaemon2 sh -c "tail -n 50 /var/log/dragonfly/daemon/stdout.log /var/log/dragonfly/daemon/stderr.log" > out.txt

dragonfly-logs-daemon3:
	@docker exec dfdaemon3 sh -c "tail -n 50 /var/log/dragonfly/daemon/stdout.log /var/log/dragonfly/daemon/stderr.log" > out.txt

minikube-delete-and-start:
	minikube start 
	helm uninstall dragonfly -n dragonfly-system
	minikube kubectl -- delete pvc --all -n dragonfly-system
	sleep 10
	helm install --wait --timeout 15m --create-namespace --namespace dragonfly-system dragonfly dragonfly/dragonfly -f values.yaml
	sudo mkdir -p /var/run/dragonfly/output #на вcякий случай
	sudo chmod 777 /var/run/dragonfly/output

minikube-get-podes: 
	kubectl get pods -n dragonfly-system --show-labels