#!/usr/bin/env bash
# shellcheck shell=bash
# После source deploy/k8s/config/.env выставляет REG_PREFIX, REG_HOST, REG_REPO_PREFIX
# (та же семантика, что resolve_registry_prefix в providers/registry/image/mapper-common.sh).
riid_registry_prefix_from_env() {
  local sel_raw="${REGISTRY_SELECTEL_NAME:-}"
  : "${sel_raw:?REGISTRY_SELECTEL_NAME is required in environment}"
  local sel="${sel_raw%/}"
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

# То же самое для реестра zstd-вариантов: REG_ZSTD_PREFIX, REG_ZSTD_HOST, REG_ZSTD_REPO_PREFIX
# из REGISTRY_SELECTEL_ZSTD_NAME. Это отдельный реестр Selectel со своим токеном,
# поэтому и префикс, и креды резолвятся независимо от основного.
riid_registry_zstd_prefix_from_env() {
  local sel_raw="${REGISTRY_SELECTEL_ZSTD_NAME:-}"
  : "${sel_raw:?REGISTRY_SELECTEL_ZSTD_NAME is required in environment for zstd mirror}"
  local sel="${sel_raw%/}"
  if [[ "$sel" == */* ]]; then
    REG_ZSTD_PREFIX="$sel"
    REG_ZSTD_HOST="${sel%%/*}"
  else
    REG_ZSTD_HOST="${REGISTRY_LOGIN_HOST:-cr.selcloud.ru}"
    REG_ZSTD_PREFIX="${REG_ZSTD_HOST}/${sel}"
  fi
  local host_slash="${REG_ZSTD_HOST}/"
  REG_ZSTD_REPO_PREFIX="${REG_ZSTD_PREFIX#"${host_slash}"}"
}
