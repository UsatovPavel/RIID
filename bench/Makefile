# Handoff-бенчмарк RIID (AGENT-72). Единственная точка запуска.
#
#   make -C bench check-env    — пререквизиты (dfdaemon, podman, curl, jar, .env)
#   make -C bench bench        — прогон, N измеряемых итераций (плюс seed)
#   make -C bench bench N=10
#   make -C bench record       — запись для bench/log/bench_log.md
#   make -C bench env-report   — версии установленных утилит
#
# Требует поднятого Dragonfly на одной ноде: ./scripts/minikube-dragonfly.sh 1
# и собранного jar: ./gradlew shadowJar

BENCH_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
PYTHON ?= python3
N ?= 1
SOURCE ?= localhost/library/jobber:latest
REGISTRY ?= http://127.0.0.1:5000
REPOSITORY ?= riid/agent97-zstd
ARTIFACTS ?= $(BENCH_DIR)/results/zstd-artifacts.json

.PHONY: bench check-env record env-report zstd-prepare zstd-check zstd-bench clean

bench:
	$(PYTHON) $(BENCH_DIR)/bench.py run --iterations $(N)

check-env:
	$(PYTHON) $(BENCH_DIR)/bench.py check-env

record:
	$(PYTHON) $(BENCH_DIR)/bench.py record

env-report:
	$(PYTHON) $(BENCH_DIR)/bench.py env-report

zstd-prepare:
	$(PYTHON) $(BENCH_DIR)/zstd_bench.py prepare --source $(SOURCE) --registry $(REGISTRY) --repository $(REPOSITORY) --output $(ARTIFACTS)

zstd-check:
	$(PYTHON) $(BENCH_DIR)/zstd_bench.py check --artifacts $(ARTIFACTS)

zstd-bench:
	$(PYTHON) $(BENCH_DIR)/zstd_bench.py run --artifacts $(ARTIFACTS) --iterations $(N)

clean:
	rm -rf $(BENCH_DIR)/results
