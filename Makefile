SHELL := /bin/bash

.PHONY: test test-client

test:
	./gradlew test
	./gradlew test --tests 'riid.client.integration.StressTest' -PincludeStress

test-client:
	./gradlew test --tests 'riid.client.*' -PincludeStress

.PHONY: test-local
test-local:
	./scripts/run_local_registry.sh
	./gradlew test --tests 'riid.client.integration.RegistryLocalTest'
