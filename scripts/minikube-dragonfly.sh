#!/bin/bash
# Minikube + Dragonfly для RIID: установка + запуск
# Запуск: ./scripts/minikube-dragonfly.sh [1|2]
#   1 — один узел (по умолчанию, driver=none)
#   2 — два узла P2P (driver=docker, none не поддерживает multi-node)
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Установка (если ещё не установлено)
# "$SCRIPT_DIR/install.sh"

# Запуск: make minikube-delete-and-start (1 node) или make minikube-2nodes (2 nodes)
NODES="${1:-1}"
if [ "$NODES" = "2" ]; then
  make minikube-2nodes
else
  make minikube-delete-and-start
fi
