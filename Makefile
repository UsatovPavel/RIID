SHELL := /bin/bash

.PHONY: test test-client
# don't use $(VAR)(hard to understand), only if really need
test:
	./gradlew test
	./gradlew test --tests 'riid.client.integration.StressTest' -PincludeStress

test-client:
	./gradlew test --tests 'riid.client.*' -PincludeStress

.PHONY: test-local
test-local:
	./gradlew test --tests 'riid.client.integration.RegistryLocalTest'

.PHONY: docker-build
docker-build:
	docker build -t riid-demo .

.PHONY: docker-test
docker-test:
	docker build --target builder -t riid-test .
	docker run --rm -v gradle-cache:/root/.gradle riid-test ./gradlew test -PdisableLocal

test-coverage:
	./gradlew jacocoTestReport
	rm -rf coverage-report
	mkdir -p coverage-report
	cp -r build/reports/jacoco/test/html/* coverage-report/