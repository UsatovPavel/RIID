#!/usr/bin/env bash
# Установка полного стека Dragonfly (manager, scheduler, seed client, dfdaemon, Redis, БД и т.д.),
# как в CI: .github/workflows/ci.yml → make -C scripts _helm-install
# Требуется: helm 3, kubectl, доступ к API кластера (KUBECONFIG).
#
# Провайдерские переопределения образов/Helm (поверх scripts/render-values-from-infra.sh):
#   DRAGONFLY_REGISTRY_PROFILE — dockerhub | selectel | local (по умолчанию dockerhub).
#   SELECTEL_ENV_FILE — .env с REGISTRY для render-selectel-dragonfly-images.sh (selectel).
# Дополнительный необязательный слой (если файл есть): deploy/k8s/providers/registry/dragonfly/values-<PROFILE>.yaml
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
RENDER_VALUES="${REPO_ROOT}/scripts/render-values-from-infra.sh"
RENDER_SELECTEL="${SCRIPT_DIR}/registry/render-selectel-dragonfly-images.sh"

TMP_VALUES="$(mktemp)"
TMP_SEL=""
TMP_MERGED=""
TMP_PROVIDER_MERGED=""

cleanup() {
  rm -f "${TMP_VALUES}" "${TMP_SEL}" "${TMP_MERGED}" "${TMP_PROVIDER_MERGED}"
}
trap cleanup EXIT

if [[ ! -f "${VALUES}" ]]; then
  echo "values not found: ${VALUES}" >&2
  exit 1
fi

if [[ ! -x "${RENDER_VALUES}" && ! -f "${RENDER_VALUES}" ]]; then
  echo "render script not found: ${RENDER_VALUES}" >&2
  exit 1
fi

bash "${RENDER_VALUES}" "${TMP_VALUES}" >/dev/null
if ! command -v yq >/dev/null 2>&1; then
  echo "yq is required (merge provider overrides + print resolved images)" >&2
  exit 1
fi

PROFILE="${DRAGONFLY_REGISTRY_PROFILE:-dockerhub}"
case "${PROFILE}" in
  dockerhub | selectel | local) ;;
  *)
    echo "install-dragonfly.sh: invalid DRAGONFLY_REGISTRY_PROFILE=${PROFILE} (use dockerhub|selectel|local)" >&2
    exit 1
    ;;
esac

HELM_VALUES="${TMP_VALUES}"

if [[ "${PROFILE}" == selectel ]]; then
  if [[ ! -f "${RENDER_SELECTEL}" ]]; then
    echo "render script not found: ${RENDER_SELECTEL}" >&2
    exit 1
  fi
  SEL_ENV="${SELECTEL_ENV_FILE:-${REPO_ROOT}/deploy/k8s/bootstrap/.env}"
  if [[ ! -f "${SEL_ENV}" ]]; then
    echo "install-dragonfly.sh (PROFILE=selectel): SELECTEL_ENV_FILE / bootstrap .env not found: ${SEL_ENV}" >&2
    exit 1
  fi
  TMP_SEL="$(mktemp)"
  TMP_MERGED="$(mktemp)"
  bash "${RENDER_SELECTEL}" "${SEL_ENV}" >"${TMP_SEL}"
  # Правая карта побеждает при конфликте ключей (образы Selectel).
  yq ea 'select(fileIndex == 0) * select(fileIndex == 1)' "${TMP_VALUES}" "${TMP_SEL}" >"${TMP_MERGED}"
  HELM_VALUES="${TMP_MERGED}"
  echo ">>> Dragonfly Helm: merged Selectel mirror overrides from ${RENDER_SELECTEL}" >&2
fi

OPTIONAL_PROVIDER_VALUES="${REPO_ROOT}/deploy/k8s/providers/registry/dragonfly/values-${PROFILE}.yaml"
if [[ -f "${OPTIONAL_PROVIDER_VALUES}" ]]; then
  TMP_PROVIDER_MERGED="$(mktemp)"
  yq ea 'select(fileIndex == 0) * select(fileIndex == 1)' "${HELM_VALUES}" "${OPTIONAL_PROVIDER_VALUES}" >"${TMP_PROVIDER_MERGED}"
  HELM_VALUES="${TMP_PROVIDER_MERGED}"
  echo ">>> Dragonfly Helm: merged optional ${OPTIONAL_PROVIDER_VALUES}" >&2
fi

echo ">>> Effective Dragonfly images (after profile=${PROFILE}):"
yq e '.manager.image, .scheduler.image, .seedClient.image, .client.image' "${HELM_VALUES}"

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
  -f "${HELM_VALUES}"

echo ">>> Pods:"
kubectl get pods -n dragonfly-system -o wide
