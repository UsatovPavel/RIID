SHELL := /bin/bash
.PHONY: docker-build docker-test dragonfly-single dragonfly-stop dragonfly-multi dragonfly-multi-stop
docker-build:
	docker build -t riid-demo .

docker-test:
	docker build --target builder -t riid-test .
	docker run --rm -v gradle-cache:/root/.gradle riid-test ./gradlew test -PdisableLocal

dragonfly-single:
	docker rm -f dfdaemon >/dev/null 2>&1 || true
	docker run -d --name dfdaemon --network host --privileged \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/dfdaemon:latest

dragonfly-stop:
	docker rm -f dfdaemon >/dev/null 2>&1 || true

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
	docker run -d --name dfdaemon1 --network dragonfly-net --privileged \
	-v "/tmp:/tmp" \
	-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/dfdaemon:latest \
		--config /etc/dragonfly/dfget-multi.yaml
	docker run -d --name dfdaemon2 --network dragonfly-net --privileged \
	-v "/tmp:/tmp" \
	-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/dfdaemon:latest \
		--config /etc/dragonfly/dfget-multi.yaml
	docker run -d --name dfdaemon3 --network dragonfly-net --privileged \
	-v "/tmp:/tmp" \
	-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/dfdaemon:latest \
		--config /etc/dragonfly/dfget-multi.yaml

dragonfly-multi-stop:
	docker rm -f dfmanager dfscheduler dfdaemon1 dfdaemon2 dfdaemon3 >/dev/null 2>&1 || true
	docker network rm dragonfly-net >/dev/null 2>&1 || true

.PHONY: dragonfly-health
dragonfly-health:
	@docker ps --filter "name=df" > out.txt 2>&1
	@echo "" >> out.txt
	@echo "dfmanager logs:" >> out.txt
	@docker logs dfmanager 2>&1 | head >> out.txt
	@echo "" >> out.txt
	@echo "dfscheduler logs:" >> out.txt
	@docker logs dfscheduler 2>&1 | head >> out.txt
	@echo "" >> out.txt
	@echo "dfdaemon1 logs:" >> out.txt
	@docker logs dfdaemon1 2>&1 | head >> out.txt

.PHONY: dragonfly-logs-manager dragonfly-logs-scheduler dragonfly-logs-daemon1 dragonfly-logs-daemon2 dragonfly-logs-daemon3
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