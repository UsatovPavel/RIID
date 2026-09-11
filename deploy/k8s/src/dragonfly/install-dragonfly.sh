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

# Pin the chart to the same version CI uses. Single source of truth is
# scripts/Makefile; the literal below is only a fallback if that parse fails.
# An unpinned install resolves to the newest upstream chart, which defaults
# manager.enable to false and raises resource requests past a small stand.
DRAGONFLY_CHART_VERSION="${DRAGONFLY_CHART_VERSION:-$(sed -n 's/^DRAGONFLY_CHART_VERSION[[:space:]]*:=[[:space:]]*//p' "${REPO_ROOT}/scripts/Makefile" 2>/dev/null | head -1)}"
DRAGONFLY_CHART_VERSION="${DRAGONFLY_CHART_VERSION:-1.6.26}"

TMP_VALUES="$(mktemp)"
TMP_MERGED=""
TMP_PROVIDER_MERGED=""

cleanup() {
  rm -f "${TMP_VALUES}" "${TMP_MERGED}" "${TMP_PROVIDER_MERGED}"
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

# dfinit rewrites one engine's registry config, but the chart values hold only one
# answer and dfinit.enable is unconditional - a containerd block left in values.yaml
# is live for EVERY arm. It once crashlooped the whole client DaemonSet ("failed to
# run container runtime: Is a directory"). Render the requested engine, null the other.
DFINIT_ENGINE="${RIID_DFINIT_ENGINE:-}"
if [ "$DFINIT_ENGINE" = "containerd" ]; then
  DFINIT_OVERRIDE="$(mktemp)"
  cleanup_dfinit_override() { rm -f "$DFINIT_OVERRIDE"; }
  trap cleanup_dfinit_override EXIT
  cat > "$DFINIT_OVERRIDE" <<DFEOF
client:
  dfinit:
    config:
      containerRuntime:
        crio: null
        containerd:
          configPath: /etc/containerd/config.toml
          proxyAllRegistries: false
          registries:
            - hostNamespace: ${RIID_DFINIT_REGISTRY:-}
              serverAddr: http://${RIID_DFINIT_REGISTRY:-}
              capabilities: ["pull", "resolve"]
DFEOF
  echo ">>> dfinit engine: containerd (crio handler disabled for this install)"
  DFINIT_HELM_ARGS="-f ${DFINIT_OVERRIDE}"
elif [ "$DFINIT_ENGINE" = "podman" ]; then
  # podman reads /etc/containers/registries.conf, same as CRI-O - dfinit has no
  # podman handler, so this uses the crio one (AGENT-99 finding). Both the crio
  # block and the proxy upstream are static leftovers pinned to an old stand's
  # registry (10.96.5.146); this renders the real registry for this cluster.
  DFINIT_OVERRIDE="$(mktemp)"
  cleanup_dfinit_override() { rm -f "$DFINIT_OVERRIDE"; }
  trap cleanup_dfinit_override EXIT
  cat > "$DFINIT_OVERRIDE" <<DFEOF
# registries.conf carries no per-request upstream the way containerd's certs.d
# header does, so every dfdaemon that may fetch from source has to be told the
# registry in its own config. Both the client (podman talks to it) and the seed
# (it is the one going back to source) default to https://index.docker.io, which
# is why a pull missed and podman silently fell back to the direct address.
client:
  config:
    proxy:
      registryMirror:
        addr: http://${RIID_DFINIT_REGISTRY:-}
  dfinit:
    config:
      containerRuntime:
        containerd: null
        crio:
          configPath: /etc/containers/registries.conf
          unqualifiedSearchRegistries: ["cr.selcloud.ru"]
          registries:
            - prefix: ${RIID_DFINIT_REGISTRY:-}
              location: ${RIID_DFINIT_REGISTRY:-}
seedClient:
  config:
    proxy:
      registryMirror:
        addr: http://${RIID_DFINIT_REGISTRY:-}
DFEOF
  echo ">>> dfinit engine: podman (crio handler, containerd disabled for this install)"
  DFINIT_HELM_ARGS="-f ${DFINIT_OVERRIDE}"
else
  DFINIT_HELM_ARGS=""
fi

echo ">>> Helm repo dragonfly"
helm repo add dragonfly https://dragonflyoss.github.io/helm-charts/ 2>/dev/null || true
helm repo update

echo ">>> helm upgrade --install dragonfly (namespace dragonfly-system), chart ${DRAGONFLY_CHART_VERSION}"
# The dfinit override MUST come after the base values: helm merges -f files in
# order and the last one wins. Passed before ${HELM_VALUES}, this override was
# erased by that file's `containerd: null`, so `dfinit-enable ENGINE=containerd`
# silently configured CRI-O instead and never wrote containerd's certs.d.
helm upgrade --install dragonfly dragonfly/dragonfly \
  --version "${DRAGONFLY_CHART_VERSION}" \
  --namespace dragonfly-system \
  --create-namespace \
  --wait \
  --timeout 15m \
  -f "${HELM_VALUES}" \
  ${DFINIT_HELM_ARGS}

# AGENT-99: dragonfly-client (hostNetwork) advertises whatever IP its default-route
# autodetection picks; where nodes clone one NAT adapter that is the same unreachable
# address everywhere and P2P degrades to 100% registry fallback. Re-applied on every
# install/upgrade - each helm upgrade re-renders the DaemonSet and undoes it.
bash "${SCRIPT_DIR}/patch-dragonfly-client-hostip.sh"

echo ">>> Pods:"
kubectl get pods -n dragonfly-system -o wide
