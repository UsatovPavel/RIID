#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
BASE_VALUES="${SCRIPT_DIR}/values.yaml"
INFRA_VERSIONS="${REPO_ROOT}/deploy/k8s/config/infra_versions.yaml"

OUT_FILE="${1:-}"
TMP_OVERRIDE="$(mktemp)"
trap 'rm -f "${TMP_OVERRIDE}"' EXIT

command -v yq >/dev/null 2>&1 || { echo "render-values-from-infra: yq is required" >&2; exit 1; }
[[ -f "${BASE_VALUES}" ]] || { echo "render-values-from-infra: missing ${BASE_VALUES}" >&2; exit 1; }
[[ -f "${INFRA_VERSIONS}" ]] || { echo "render-values-from-infra: missing ${INFRA_VERSIONS}" >&2; exit 1; }

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

for key in manager scheduler client seed_client; do
  img="$(yq e ".dragonfly.images.${key}.image" "${INFRA_VERSIONS}")"
  [[ -n "${img}" && "${img}" != "null" ]] || {
    echo "render-values-from-infra: missing dragonfly.images.${key}.image in ${INFRA_VERSIONS}" >&2
    exit 1
  }
done

read -r mgr_reg mgr_repo mgr_tag < <(parse_ref "$(yq e '.dragonfly.images.manager.image' "${INFRA_VERSIONS}")")
read -r sch_reg sch_repo sch_tag < <(parse_ref "$(yq e '.dragonfly.images.scheduler.image' "${INFRA_VERSIONS}")")
read -r cli_reg cli_repo cli_tag < <(parse_ref "$(yq e '.dragonfly.images.client.image' "${INFRA_VERSIONS}")")
read -r seed_reg seed_repo seed_tag < <(parse_ref "$(yq e '.dragonfly.images.seed_client.image' "${INFRA_VERSIONS}")")

yq e -n \
  --arg mgr_reg "${mgr_reg}" --arg mgr_repo "${mgr_repo}" --arg mgr_tag "${mgr_tag}" \
  --arg sch_reg "${sch_reg}" --arg sch_repo "${sch_repo}" --arg sch_tag "${sch_tag}" \
  --arg cli_reg "${cli_reg}" --arg cli_repo "${cli_repo}" --arg cli_tag "${cli_tag}" \
  --arg seed_reg "${seed_reg}" --arg seed_repo "${seed_repo}" --arg seed_tag "${seed_tag}" \
  '
  .manager.image.registry = $mgr_reg |
  .manager.image.repository = $mgr_repo |
  .manager.image.tag = $mgr_tag |
  .scheduler.image.registry = $sch_reg |
  .scheduler.image.repository = $sch_repo |
  .scheduler.image.tag = $sch_tag |
  .client.image.registry = $cli_reg |
  .client.image.repository = $cli_repo |
  .client.image.tag = $cli_tag |
  .seedClient.image.registry = $seed_reg |
  .seedClient.image.repository = $seed_repo |
  .seedClient.image.tag = $seed_tag
  ' > "${TMP_OVERRIDE}"

if [[ -n "${OUT_FILE}" ]]; then
  yq ea 'select(fileIndex == 0) * select(fileIndex == 1)' "${BASE_VALUES}" "${TMP_OVERRIDE}" > "${OUT_FILE}"
  echo "render-values-from-infra: wrote ${OUT_FILE}" >&2
else
  yq ea 'select(fileIndex == 0) * select(fileIndex == 1)' "${BASE_VALUES}" "${TMP_OVERRIDE}"
fi
