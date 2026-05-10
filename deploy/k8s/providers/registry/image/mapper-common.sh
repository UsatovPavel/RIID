#!/usr/bin/env bash
set -euo pipefail

# Каталог этого файла (корректно при source из других скриптов).
MAPPER_COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Записать config/imagelist/{selectel,local}.yaml из dockerhub.yaml (см. imagelist_emit_overlays.py).
imagelist_build_overlays_from_dockerhub() {
  local dockerhub_yaml="$1"
  local out_selectel="$2"
  local out_local="$3"
  local env_file="${4:-}"
  command -v python3 >/dev/null 2>&1 || {
    echo "imagelist_build_overlays_from_dockerhub: python3 required" >&2
    return 1
  }
  command -v yq >/dev/null 2>&1 || {
    echo "imagelist_build_overlays_from_dockerhub: yq required" >&2
    return 1
  }
  if [[ -n "${env_file}" && -f "${env_file}" && -r "${env_file}" ]]; then
    python3 "${MAPPER_COMMON_DIR}/imagelist_emit_overlays.py" \
      "${dockerhub_yaml}" "${out_selectel}" "${out_local}" "${env_file}"
  else
    python3 "${MAPPER_COMMON_DIR}/imagelist_emit_overlays.py" \
      "${dockerhub_yaml}" "${out_selectel}" "${out_local}"
  fi
}

resolve_registry_prefix() {
  local env_file="$1"
  if [[ ! -f "$env_file" ]]; then
    echo "mapper: env file not found: $env_file" >&2
    return 1
  fi

  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a

  : "${REGISTRY_SELECTEL_ID:?mapper: REGISTRY_SELECTEL_ID is required in $env_file for selectel mapping}"

  local sel="${REGISTRY_SELECTEL_ID%/}"
  if [[ "$sel" == */* ]]; then
    REG_PREFIX="$sel"
    REG_HOST="${sel%%/*}"
  else
    REG_HOST="${REGISTRY_LOGIN_HOST:-cr.selcloud.ru}"
    REG_PREFIX="${REG_HOST}/${sel}"
  fi

  local host_slash="${REG_HOST}/"
  REG_REPO_PREFIX="${REG_PREFIX#"${host_slash}"}"
}

strip_library_if_needed() {
  local repo="$1"
  local strip="$2"
  if [[ "$strip" == "1" && "$repo" == library/* ]]; then
    echo "${repo#library/}"
  else
    echo "$repo"
  fi
}

map_repo_by_provider() {
  local provider="$1"
  local repo="$2"
  local env_file="${3:-}"
  local mapped="$repo"

  case "$provider" in
    dockerhub)
      echo "$repo"
      ;;
    selectel)
      : "${env_file:?mapper: env file is required for selectel provider}"
      resolve_registry_prefix "$env_file"

      local strip_selectel="${REGISTRY_PUSH_REPO_STRIP_LIBRARY:-}"
      if [[ -z "$strip_selectel" ]]; then
        if [[ "$REG_HOST" == *selcloud.ru ]]; then
          strip_selectel=1
        else
          strip_selectel=0
        fi
      fi
      mapped="$(strip_library_if_needed "$repo" "$strip_selectel")"
      echo "${REG_REPO_PREFIX}/${mapped}"
      ;;
    local)
      local strip_local="${MAPPER_STRIP_LIBRARY_LOCAL:-1}"
      local prefix_local="${MAPPER_LOCAL_REPO_PREFIX:-riid}"
      mapped="$(strip_library_if_needed "$repo" "$strip_local")"
      echo "${prefix_local%/}/${mapped}"
      ;;
    *)
      echo "mapper: unsupported provider '$provider' (use dockerhub|selectel|local)" >&2
      return 1
      ;;
  esac
}
