# Handoff-бенчмарк RIID (AGENT-72). Единственная точка запуска.
#
#   make -C bench check-env    — пререквизиты (dfdaemon, podman, curl, jar, .env)
#   make -C bench bench        — прогон, N измеряемых итераций (плюс seed)
#   make -C bench bench N=10
#   make -C bench record       — 3 строки для Optimization.md
#   make -C bench env-report   — версии установленных утилит
#
# Требует поднятого Dragonfly на одной ноде: ./scripts/minikube-dragonfly.sh 1
# и собранного jar: ./gradlew shadowJar

BENCH_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
PYTHON ?= python3
N ?= 1

.PHONY: bench check-env record env-report clean

bench:
	$(PYTHON) $(BENCH_DIR)/bench.py run --iterations $(N)

check-env:
	$(PYTHON) $(BENCH_DIR)/bench.py check-env

record:
	$(PYTHON) $(BENCH_DIR)/bench.py record

env-report:
	$(PYTHON) $(BENCH_DIR)/bench.py env-report

clean:
	rm -rf $(BENCH_DIR)/results
