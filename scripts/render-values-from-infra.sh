#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
BASE_VALUES="${SCRIPT_DIR}/values.yaml"
IMAGE_LIST="${IMAGE_LIST_FILE:-${REPO_ROOT}/deploy/k8s/config/imagelist/dockerhub.yaml}"
CLUSTER_CONFIG="${CLUSTER_CONFIG:-${REPO_ROOT}/deploy/k8s/config/config.yaml}"

OUT_FILE="${1:-}"
TMP_OVERRIDE="$(mktemp)"
trap 'rm -f "${TMP_OVERRIDE}"' EXIT

command -v yq >/dev/null 2>&1 || { echo "render-values-from-infra: yq is required" >&2; exit 1; }
[[ -f "${BASE_VALUES}" ]] || { echo "render-values-from-infra: missing ${BASE_VALUES}" >&2; exit 1; }
[[ -f "${IMAGE_LIST}" ]] || { echo "render-values-from-infra: missing ${IMAGE_LIST}" >&2; exit 1; }

infra_image() {
  local key="$1"
  local img
  img="$(yq e ".infra.images[\"${key}\"]" "${IMAGE_LIST}")"
  [[ -n "${img}" && "${img}" != "null" ]] || return 1
  printf '%s\n' "${img}"
}

parse_ref() {
  local ref="$1"
  local image_part tag_part first registry repo

  image_part="${ref%@*}"
  if [[ "${image_part}" == "${ref}" ]]; then
    image_part="${ref}"
  fi

  tag_part="${image_part##*:}"
  if [[ "${tag_part}" == "${image_part}" || "${image_part##*/}" == "${tag_part}" ]]; then
    echo "render-values-from-infra: image has no tag: ${ref}" >&2
    return 1
  fi

  image_part="${image_part%:*}"
  first="${image_part%%/*}"
  if [[ "${first}" == "localhost" || "${first}" == *.* || "${first}" == *:* ]]; then
    registry="${first}"
    repo="${image_part#*/}"
  else
    registry="docker.io"
    repo="${image_part}"
  fi

  printf '%s\t%s\t%s\n' "${registry}" "${repo}" "${tag_part}"
}

for key in dragonfly.manager dragonfly.scheduler dragonfly.client dragonfly.seed_client; do
  infra_image "${key}" >/dev/null || {
    echo "render-values-from-infra: missing infra.images[\"${key}\"] in ${IMAGE_LIST}" >&2
    exit 1
  }
done

read -r mgr_reg mgr_repo mgr_tag < <(parse_ref "$(infra_image dragonfly.manager)")
read -r sch_reg sch_repo sch_tag < <(parse_ref "$(infra_image dragonfly.scheduler)")
read -r cli_reg cli_repo cli_tag < <(parse_ref "$(infra_image dragonfly.client)")
read -r seed_reg seed_repo seed_tag < <(parse_ref "$(infra_image dragonfly.seed_client)")

SEED_CLIENTS=2
if [[ -f "${CLUSTER_CONFIG}" ]]; then
  SEED_CLIENTS_FROM_CONFIG="$(yq e '.dragonfly.seed_clients // ""' "${CLUSTER_CONFIG}" 2>/dev/null || echo "")"
  if [[ "${SEED_CLIENTS_FROM_CONFIG}" =~ ^[0-9]+$ ]] && [[ "${SEED_CLIENTS_FROM_CONFIG}" -gt 0 ]]; then
    SEED_CLIENTS="${SEED_CLIENTS_FROM_CONFIG}"
  fi
fi

cat > "${TMP_OVERRIDE}" <<EOF
manager:
  image:
    registry: ${mgr_reg}
    repository: ${mgr_repo}
    tag: ${mgr_tag}
scheduler:
  image:
    registry: ${sch_reg}
    repository: ${sch_repo}
    tag: ${sch_tag}
client:
  image:
    registry: ${cli_reg}
    repository: ${cli_repo}
    tag: ${cli_tag}
seedClient:
  replicas: ${SEED_CLIENTS}
  image:
    registry: ${seed_reg}
    repository: ${seed_repo}
    tag: ${seed_tag}
EOF

if [[ -n "${OUT_FILE}" ]]; then
  yq ea 'select(fileIndex == 0) * select(fileIndex == 1)' "${BASE_VALUES}" "${TMP_OVERRIDE}" > "${OUT_FILE}"
  echo "render-values-from-infra: wrote ${OUT_FILE}" >&2
else
  yq ea 'select(fileIndex == 0) * select(fileIndex == 1)' "${BASE_VALUES}" "${TMP_OVERRIDE}"
fi
