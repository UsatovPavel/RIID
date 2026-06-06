#!/usr/bin/env bash
# Print RIID POST /pull "repository" string из imagelist (config → test_registry_provider).
#
# Usage:
#   smoke-resolve-repository.sh <docker_hub_repository>
#   smoke-resolve-repository.sh --logical-key <key>
# Опции второго вида пробрасываются в resolve_smoke_repository.py (можно добавить --json).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PY="${K8S_ROOT}/providers/registry/image/resolve_smoke_repository.py"

[[ -f "$PY" ]] || {
  echo "$0: missing $PY" >&2
  exit 1
}

if [[ "${1:-}" == "--logical-key" ]]; then
  [[ -n "${2:-}" ]] || {
    echo "$0: usage: $0 --logical-key <key> [--json …]" >&2
    exit 1
  }
  exec python3 "$PY" --k8s-root "$K8S_ROOT" --logical-key "$2" "${@:3}"
fi

HUB_REPO="${1:?usage: $0 <docker_hub_repository> | $0 --logical-key <key>}"

exec python3 "$PY" --k8s-root "$K8S_ROOT" --docker-hub-repository "$HUB_REPO"
