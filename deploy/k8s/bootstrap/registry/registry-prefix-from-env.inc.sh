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

# The same for the registry of zstd variants: REG_ZSTD_PREFIX, REG_ZSTD_HOST and
# REG_ZSTD_REPO_PREFIX from REGISTRY_SELECTEL_ZSTD_NAME. It is a separate Selectel
# registry with its own token, so both the prefix and the credentials are
# resolved independently of the main one.
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
