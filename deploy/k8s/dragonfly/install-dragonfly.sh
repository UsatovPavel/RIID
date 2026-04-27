#!/usr/bin/env bash
# Установка полного стека Dragonfly (manager, scheduler, seed client, dfdaemon, Redis, БД и т.д.),
# как в CI: .github/workflows/ci.yml → make -C scripts _helm-install
# Требуется: helm 3, kubectl, доступ к API кластера (KUBECONFIG).
set -euo pipefail

if ! command -v helm >/dev/null 2>&1; then
  echo "helm: command not found. On Ubuntu/WSL there is often no apt package named 'helm'." >&2
  echo "Install Helm 3 (official script, same as CI scripts/ci-dragonfly-setup.sh):" >&2
  echo "  curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash" >&2
  echo "Then re-run this script." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
VALUES="${REPO_ROOT}/scripts/values.yaml"

if [[ ! -f "${VALUES}" ]]; then
  echo "values not found: ${VALUES}" >&2
  exit 1
fi

# Без KUBECONFIG helm/kubectl идут на http://127.0.0.1:8080. При отсутствии переменной
# пробуем стандартный путь Selectech; иначе явный export.
if [[ -z "${KUBECONFIG:-}" ]]; then
  CANDIDATE_KUBECONFIG="${REPO_ROOT}/deploy/k8s/Selectech/serverConfig.yaml"
  if [[ -f "${CANDIDATE_KUBECONFIG}" ]]; then
    export KUBECONFIG="${CANDIDATE_KUBECONFIG}"
    echo ">>> KUBECONFIG not set: using ${KUBECONFIG}" >&2
  else
    echo "KUBECONFIG is not set. Point it at your cluster kubeconfig, e.g.:" >&2
    echo "  export KUBECONFIG=\"\$PWD/deploy/k8s/Selectech/serverConfig.yaml\"" >&2
    echo "  # or: make -C deploy/k8s/Selectech connect  (stays in a shell with the same KUBECONFIG)" >&2
    exit 1
  fi
fi

if ! kubectl cluster-info &>/dev/null; then
  echo "kubectl cannot reach the API (check KUBECONFIG and context). Current KUBECONFIG: ${KUBECONFIG}" >&2
  exit 1
fi

echo ">>> Helm repo dragonfly"
helm repo add dragonfly https://dragonflyoss.github.io/helm-charts/ 2>/dev/null || true
helm repo update

echo ">>> helm upgrade --install dragonfly (namespace dragonfly-system)"
helm upgrade --install dragonfly dragonfly/dragonfly \
  --namespace dragonfly-system \
  --create-namespace \
  --wait \
  --timeout 15m \
  -f "${VALUES}"

echo ">>> Pods:"
kubectl get pods -n dragonfly-system -o wide
