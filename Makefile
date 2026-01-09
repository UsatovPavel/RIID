SHELL := /bin/bash

.PHONY: test test-client build check reports-merge
# don't use $(VAR)(hard to understand), only if really need

check:
	./gradlew check jacocoTestReport
	@$(MAKE) reports-merge
	

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

reports-merge:
	@mkdir -p build/reports
	@reports="build/reports/checkstyle/main.html build/reports/checkstyle/test.html build/reports/pmd/main.html build/reports/pmd/test.html build/reports/spotbugs/main.html build/reports/spotbugs/test.html build/reports/problems/problems-report.html"; \
	out="build/reports/all-reports.html"; \
	echo "<html><head><meta charset='UTF-8'><title>All Reports</title></head><body><h1>Quality Reports</h1>" > $$out; \
	for f in $$reports; do \
	  if [ -f $$f ]; then \
	    rel=$${f#build/reports/}; \
	    echo "<section><h2>$$rel</h2><iframe src='$$rel' style='width:100%;height:600px;border:1px solid #ccc;'></iframe></section><hr/>" >> $$out; \
	  fi; \
	done; \
	echo "</body></html>" >> $$out; \
	echo "Combined report generated at $$out"
