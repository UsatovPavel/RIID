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
	docker rm -f dfmanager dfscheduler dfdaemon1 dfdaemon2 dfdaemon3 >/dev/null 2>&1 || true
	docker run -d --name dfmanager --network dragonfly-net \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		-e MANAGER_CONFIG=/etc/dragonfly/manager.yaml \
		dragonflyoss/manager:latest \
		--config /etc/dragonfly/manager.yaml --console
	docker run -d --name dfscheduler --network dragonfly-net \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/scheduler:latest \
		--config /etc/dragonfly/scheduler.yaml --console
	docker run -d --name dfdaemon1 --network dragonfly-net --privileged \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/dfdaemon:latest
	docker run -d --name dfdaemon2 --network dragonfly-net --privileged \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/dfdaemon:latest
	docker run -d --name dfdaemon3 --network dragonfly-net --privileged \
		-v "$(PWD)/config/dragonfly":/etc/dragonfly:ro \
		dragonflyoss/dfdaemon:latest

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