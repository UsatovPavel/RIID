SHELL := /bin/bash
.PHONY: docker-build
docker-build:
	docker build -t riid-demo .

.PHONY: docker-test
docker-test:
	docker build --target builder -t riid-test .
	docker run --rm -v gradle-cache:/root/.gradle riid-test ./gradlew test -PdisableLocal