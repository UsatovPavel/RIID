#!/usr/bin/env bash
# Установка полного стека Dragonfly (manager, scheduler, seed client, dfdaemon, Redis, БД и т.д.),
# как в CI: .github/workflows/ci.yml → make -C scripts _helm-install
# Требуется: helm 3, kubectl, доступ к API кластера (KUBECONFIG).
#
# Провайдерские переопределения образов/Helm (поверх scripts/render-values-from-infra.sh):
#   DRAGONFLY_REGISTRY_PROFILE — dockerhub | selectel | local (по умолчанию dockerhub).
#   Selectel: слой образов берётся из deploy/k8s/.resolved/registry/helm/dragonfly-values-selectel.yaml
#   (пишется при make imagelist-overlays или provider-apply).
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
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
VALUES="${REPO_ROOT}/scripts/values.yaml"
RENDER_VALUES="${REPO_ROOT}/scripts/render-values-from-infra.sh"
SELECTEL_HELM_FRAGMENT="${REPO_ROOT}/deploy/k8s/.resolved/registry/helm/dragonfly-values-selectel.yaml"

TMP_VALUES="$(mktemp)"
TMP_MERGED=""
TMP_PROVIDER_MERGED=""
TMP_DFINIT=""
TMP_DFINIT_MERGED=""

cleanup() {
  rm -f "${TMP_VALUES}" "${TMP_MERGED}" "${TMP_PROVIDER_MERGED}" "${TMP_DFINIT}" "${TMP_DFINIT_MERGED}"
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
  if [[ ! -f "${SELECTEL_HELM_FRAGMENT}" ]]; then
    echo "install-dragonfly.sh (PROFILE=selectel): resolved Helm fragment not found: ${SELECTEL_HELM_FRAGMENT}" >&2
    echo "Run: make -C deploy/k8s/providers imagelist-overlays CONFIG_ENV=deploy/k8s/config/.env" >&2
    echo "  or: bash deploy/k8s/providers/registry/image/provider-apply.sh (after overlays)" >&2
    exit 1
  fi
  TMP_MERGED="$(mktemp)"
  # Правая карта побеждает при конфликте ключей (образы Selectel).
  yq ea 'select(fileIndex == 0) * select(fileIndex == 1)' "${TMP_VALUES}" "${SELECTEL_HELM_FRAGMENT}" >"${TMP_MERGED}"
  HELM_VALUES="${TMP_MERGED}"
  echo ">>> Dragonfly Helm: merged Selectel fragment from ${SELECTEL_HELM_FRAGMENT}" >&2
fi

# AGENT-74, the dfinit arm. dfinit is an initContainer of the client DaemonSet
# that writes a registry mirror pointing at the dfdaemon proxy into the ENGINE's
# own config on the node. It is off in scripts/values.yaml, so an ordinary
# install is untouched; RIID_DFINIT_ENGINE turns it on for the one arm that
# needs it, and RIID_DFINIT_ENGINE= (empty) turns it back off.
#
# containerd is set to null on purpose. The chart ships a default
# containerRuntime.containerd block, and the DaemonSet template picks the first
# runtime present in a fixed if/else-if order — containerd, crio, podman,
# docker. Leaving the default in place means dfinit keeps rewriting
# /etc/containerd/config.toml while the podman arm waits for a mirror in
# /etc/containers/registries.conf that never arrives. Helm removes a key whose
# value is null, which is the only way to drop a default from a values file.
DFINIT_ENGINE="${RIID_DFINIT_ENGINE:-}"
if [[ -n "${DFINIT_ENGINE}" ]]; then
  : "${RIID_DFINIT_REGISTRY:?RIID_DFINIT_ENGINE needs RIID_DFINIT_REGISTRY=host[:port] — the address the engine pulls from}"
  TMP_DFINIT="$(mktemp)"
  case "${DFINIT_ENGINE}" in
    podman)
      cat >"${TMP_DFINIT}" <<EOF
client:
  dfinit:
    enable: true
    config:
      containerRuntime:
        containerd: null
        podman:
          configPath: /etc/containers/registries.conf
          registries:
            - prefix: "${RIID_DFINIT_REGISTRY}"
              location: "${RIID_DFINIT_REGISTRY}"
EOF
      ;;
    containerd)
      cat >"${TMP_DFINIT}" <<EOF
client:
  dfinit:
    enable: true
    config:
      containerRuntime:
        containerd:
          configPath: /etc/containerd/config.toml
          registries:
            - hostNamespace: "${RIID_DFINIT_REGISTRY}"
              serverAddr: "http://${RIID_DFINIT_REGISTRY}"
              capabilities: ["pull", "resolve"]
              skipVerify: true
EOF
      ;;
    *)
      echo "install-dragonfly.sh: invalid RIID_DFINIT_ENGINE=${DFINIT_ENGINE} (use podman|containerd)" >&2
      exit 1
      ;;
  esac
  TMP_DFINIT_MERGED="$(mktemp)"
  yq ea 'select(fileIndex == 0) * select(fileIndex == 1)' "${HELM_VALUES}" "${TMP_DFINIT}" >"${TMP_DFINIT_MERGED}"
  HELM_VALUES="${TMP_DFINIT_MERGED}"
  echo ">>> Dragonfly Helm: dfinit enabled for ${DFINIT_ENGINE}, mirroring ${RIID_DFINIT_REGISTRY}" >&2
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
# пробуем путь Selectel из providers/cluster; иначе явный export.
if [[ -z "${KUBECONFIG:-}" ]]; then
  CANDIDATE_KUBECONFIG="${REPO_ROOT}/deploy/k8s/providers/cluster/Selectel/serverConfig.yaml"
  if [[ -f "${CANDIDATE_KUBECONFIG}" ]]; then
    export KUBECONFIG="${CANDIDATE_KUBECONFIG}"
    echo ">>> KUBECONFIG not set: using ${KUBECONFIG}" >&2
  else
    echo "KUBECONFIG is not set. Point it at your cluster kubeconfig, e.g.:" >&2
    echo "  export KUBECONFIG=\"\$PWD/deploy/k8s/providers/cluster/Selectel/serverConfig.yaml\"" >&2
    echo "  # or: make -C deploy/k8s/bootstrap connect  (stays in a shell with the same KUBECONFIG)" >&2
    exit 1
  fi
fi

if ! kubectl cluster-info &>/dev/null; then
  echo "kubectl cannot reach the API (check KUBECONFIG and context). Current KUBECONFIG: ${KUBECONFIG}" >&2
  exit 1
fi

# The chart version is part of the recorded environment (AGENT-99), and it is not
# cosmetic: without --version helm takes whatever is newest in the repo, and a
# chart built for a newer appVersion renders a config the pinned images cannot
# parse — chart 1.8.2 against scheduler v2.4.4-rc.1 dies with
# "manager requires parameter addr" in CrashLoopBackOff. The pin lives with the
# images, in config/imagelist/dockerhub.yaml (.infra.dragonfly.helm_chart).
CHART_VERSION="${DRAGONFLY_CHART_VERSION:-}"
if [[ -z "${CHART_VERSION}" ]]; then
  CHART_TAG="$(yq e '.infra.dragonfly.helm_chart.github_release_tag // ""' \
    "${REPO_ROOT}/deploy/k8s/config/imagelist/dockerhub.yaml" 2>/dev/null || true)"
  CHART_VERSION="${CHART_TAG#dragonfly-}"
fi
if [[ -z "${CHART_VERSION}" ]]; then
  echo "install-dragonfly.sh: no chart version (set DRAGONFLY_CHART_VERSION or .infra.dragonfly.helm_chart.github_release_tag)" >&2
  exit 1
fi
echo ">>> Dragonfly chart version: ${CHART_VERSION}" >&2

echo ">>> Helm repo dragonfly"
helm repo add dragonfly https://dragonflyoss.github.io/helm-charts/ 2>/dev/null || true
helm repo update

echo ">>> helm upgrade --install dragonfly (namespace dragonfly-system)"
helm upgrade --install dragonfly dragonfly/dragonfly \
  --version "${CHART_VERSION}" \
  --namespace dragonfly-system \
  --create-namespace \
  --wait \
  --timeout 15m \
  -f "${HELM_VALUES}"

echo ">>> Pods:"
kubectl get pods -n dragonfly-system -o wide
