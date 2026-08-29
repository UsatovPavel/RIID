#!/usr/bin/env bash
# Зеркалирует образы в приватный реестр для performance/smoke сценариев.
#
# Список имён — объединение без дубликатов (A ∪ B):
#   A — строки TSV (по умолчанию images_list.sizes.tsv): колонка repository + колонка tag
#       (если tag пустой — берётся POPULAR_IMAGES_REFERENCE из Java).
#   B — репозитории из PopularDockerHubImagesFromProgramDocs.java; только те, которых ещё нет в A; тег для них — POPULAR_IMAGES_REFERENCE.
# Повторные строки заголовка (repository<TAB>tag) пропускаются.
#
# RIID_IMAGES_LIST может указывать на альтернативный TSV (например infra_images_list.tsv).
# Поддерживаются как docker.io-style репозитории (library/nginx), так и fully-qualified
# источники (registry.k8s.io/ns/repo, ghcr.io/org/repo). Для fully-qualified источника
# registry host отбрасывается только в destination path.
#
# Реестр назначения (push): из REGISTRY_SELECTEL_NAME (+ опционально REGISTRY_LOGIN_HOST), см. mapper-common / .env.example.
#   Полный префикс допускается в REGISTRY_SELECTEL_NAME (host/путь).
#
# Selectel: в доке лимит длины имени образа; пути вида library/hello-seattle (21 символ) реестр отклоняет
# (docker push после ретраев: unknown:). Для *selcloud.ru по умолчанию push идёт без префикса library/:
#   cr.selcloud.ru/<id>/hello-seattle:latest
# После успешного зеркала пишется performance-registry-smoke-map.tsv: docker_hub_repository<TAB>riid_repository<TAB>reference (тег зеркала).
# Смок Selectel: SMOKE_REFERENCE должен совпадать с reference для выбранного образа (колонка 3 в map или тег из TSV).
# Отключить strip library/: REGISTRY_PUSH_REPO_STRIP_LIBRARY=0
#
# Неудачные pull/tag/push пишутся в out/unsuccessfule_downloads.txt, цикл не прерывается.
# В out/performance-registry-smoke-map.tsv попадают только успешно зеркалированные репы.
#
# Режим только списка (без ∪ Java): REGISTRY_MIRROR_TSV_ONLY=1 — для инкрементального зеркала по урезанному TSV.
# Дописать строки в smoke-map без перезатирания: REGISTRY_MIRROR_APPEND_SMOKE_MAP=1.
# Дописать блок в unsuccessfule_downloads.txt: REGISTRY_MIRROR_APPEND_FAIL_LOG=1.
#
# library/clefos: на Docker Hub нет манифеста linux/amd64 — в скрипте пропуск. Доп. пропуски: REGISTRY_MIRROR_SKIP="ns/repo ns/repo2"
#
# Second leg — zstd (REGISTRY_MIRROR_ZSTD=1): the same image is additionally
# pushed to the REGISTRY_SELECTEL_ZSTD_NAME registry with its layers repacked in
# zstd. The method comes from AGENT-97 (bench/zstd_bench.py):
# podman push --format oci --compression-format zstd --force-compression.
# docker push will not do here: it has no --compression-format, so this leg
# requires podman.
# The repack source is the gzip mirror just pushed, not Docker Hub: the bytes are
# the same and no Hub pull limits are spent. Level: REGISTRY_MIRROR_ZSTD_LEVEL
# (3 by default).
# Result: out/performance-registry-zstd-map.tsv (repo, zstd path, tag, manifest digest).
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DIR/../../../../.." && pwd)"
OUT_DIR="${DIR}/out"
ENV_FILE="${1:-$REPO_ROOT/deploy/k8s/config/.env}"
JAVA_LIST="$REPO_ROOT/src/testFixtures/java/riid/config/PopularDockerHubImagesFromProgramDocs.java"
IMAGES_LIST_FILE="${RIID_IMAGES_LIST:-$DIR/../images_list.sizes.tsv}"
if [[ "$IMAGES_LIST_FILE" != /* ]]; then
  IMAGES_LIST_FILE="$REPO_ROOT/$IMAGES_LIST_FILE"
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "init-performance-registry-images: нет $ENV_FILE (задайте путь: $0 /path/.env)" >&2
  exit 1
fi
if [[ ! -f "$IMAGES_LIST_FILE" ]]; then
  echo "init-performance-registry-images: не найден $IMAGES_LIST_FILE (задайте RIID_IMAGES_LIST или положите images_list.sizes.tsv рядом со скриптом)" >&2
  exit 1
fi

if [[ "${REGISTRY_MIRROR_TSV_ONLY:-}" != 1 ]] && [[ ! -f "$JAVA_LIST" ]]; then
  echo "init-performance-registry-images: не найден $JAVA_LIST" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

# shellcheck source=../registry-prefix-from-env.inc.sh
source "$DIR/../registry-prefix-from-env.inc.sh"
riid_registry_prefix_from_env

: "${RIID_SELECTEL_USER:?в $ENV_FILE нужен RIID_SELECTEL_USER}"
SELECTEL_PASS="${RIID_SELECTEL_TOKEN:-${RIID_SELECTEL_PASSWORD:-}}"
: "${SELECTEL_PASS:?в $ENV_FILE нужен RIID_SELECTEL_TOKEN (или RIID_SELECTEL_PASSWORD)}"

if [[ -z "${REGISTRY_PUSH_REPO_STRIP_LIBRARY:-}" ]]; then
  if [[ "$REG_HOST" == *selcloud.ru ]]; then
    REGISTRY_PUSH_REPO_STRIP_LIBRARY=1
  else
    REGISTRY_PUSH_REPO_STRIP_LIBRARY=0
  fi
fi

MIRROR_ZSTD="${REGISTRY_MIRROR_ZSTD:-0}"
ZSTD_LEVEL="${REGISTRY_MIRROR_ZSTD_LEVEL:-3}"
ZSTD_AUTHFILE=""
if [[ "$MIRROR_ZSTD" == 1 ]]; then
  command -v podman >/dev/null 2>&1 || {
    echo "init-performance-registry-images: REGISTRY_MIRROR_ZSTD=1 требует podman (docker push не умеет --compression-format)" >&2
    exit 1
  }
  riid_registry_zstd_prefix_from_env
  : "${RIID_SELECTEL_ZSTD_USER:?в $ENV_FILE нужен RIID_SELECTEL_ZSTD_USER}"
  ZSTD_PASS="${RIID_SELECTEL_ZSTD_TOKEN:-${RIID_SELECTEL_ZSTD_PASSWORD:-}}"
  : "${ZSTD_PASS:?в $ENV_FILE нужен RIID_SELECTEL_ZSTD_TOKEN (или RIID_SELECTEL_ZSTD_PASSWORD)}"

  # Both registries live on one host, and podman login stores credentials per
  # host — the main registry's token would overwrite the zstd one and the push
  # would fail on blob upload. containers-auth.json allows a key with a namespace
  # (host/registry) that takes precedence over the host key, so the authfile is
  # written with both explicitly.
  ZSTD_AUTHFILE="$(mktemp -t riid-zstd-auth-XXXXXX.json)"
  chmod 600 "$ZSTD_AUTHFILE"
  trap 'rm -f "$ZSTD_AUTHFILE"' EXIT
  RIID_SELECTEL_USER="$RIID_SELECTEL_USER" SELECTEL_PASS="$SELECTEL_PASS" \
  RIID_SELECTEL_ZSTD_USER="$RIID_SELECTEL_ZSTD_USER" ZSTD_PASS="$ZSTD_PASS" \
  REG_PREFIX="$REG_PREFIX" REG_ZSTD_PREFIX="$REG_ZSTD_PREFIX" \
  python3 -c '
import base64, json, os
def entry(u, p):
    return {"auth": base64.b64encode(f"{u}:{p}".encode()).decode()}
auths = {
    os.environ["REG_PREFIX"]: entry(os.environ["RIID_SELECTEL_USER"], os.environ["SELECTEL_PASS"]),
    os.environ["REG_ZSTD_PREFIX"]: entry(os.environ["RIID_SELECTEL_ZSTD_USER"], os.environ["ZSTD_PASS"]),
}
print(json.dumps({"auths": auths}, indent=2))
' > "$ZSTD_AUTHFILE"
  echo ">>> zstd-зеркало включено: $REG_ZSTD_PREFIX (level $ZSTD_LEVEL)" >&2
fi

REF="$(grep -E '^\s*public static final String POPULAR_IMAGES_REFERENCE' "$JAVA_LIST" | sed -n 's/.*= *"\([^"]*\)".*/\1/p')"
[[ -n "$REF" ]] || REF="latest"

mapfile -t JAVA_REPOS < <(grep -E '^\s+"library/' "$JAVA_LIST" | sed -n 's/.*"\(library\/[^"]*\)".*/\1/p')
if [[ "${REGISTRY_MIRROR_TSV_ONLY:-}" != 1 ]]; then
  if [[ "${#JAVA_REPOS[@]}" -ne 30 ]]; then
    echo "init-performance-registry-images: ожидалось 30 репозиториев в $JAVA_LIST, получено ${#JAVA_REPOS[@]}" >&2
    exit 1
  fi
fi

declare -A _seen_union_repos
REPOS=()
REPO_REFS=() # пусто = использовать REF; иначе docker pull/push с этим тегом

_add_union_repo() {
  local r="$1"
  local t="${2-}"
  [[ -z "$r" ]] && return
  if [[ -n "${_seen_union_repos[$r]:-}" ]]; then
    return
  fi
  _seen_union_repos[$r]=1
  REPOS+=("$r")
  REPO_REFS+=("$t")
}

_mirror_skip_repo() {
  local r="$1"
  [[ "$r" == library/clefos ]] && return 0
  local s
  for s in ${REGISTRY_MIRROR_SKIP:-}; do
    [[ -n "$s" && "$r" == "$s" ]] && return 0
  done
  return 1
}

_is_fully_qualified_repo() {
  local repo="$1"
  local first="${repo%%/*}"
  [[ "$first" == "localhost" || "$first" == *.* || "$first" == *:* ]]
}

while IFS=$'\t' read -r repo tcol || [[ -n "${repo:-}" ]]; do
  [[ -z "${repo:-}" ]] && continue
  _add_union_repo "$repo" "${tcol:-}"
done < <(awk -F '\t' '
  BEGIN { OFS = "\t" }
  tolower($1) == "repository" && tolower($2) == "tag" { next }
  $1 == "" { next }
  $1 ~ /^#/ { next }
  {
    gsub(/\r/, "", $1)
    gsub(/\r/, "", $2)
    if ($1 != "") print $1, $2
  }
' "$IMAGES_LIST_FILE")

if [[ "${REGISTRY_MIRROR_TSV_ONLY:-}" != 1 ]]; then
  for j in "${JAVA_REPOS[@]}"; do
    _add_union_repo "$j" ""
  done
fi

if [[ "${#REPOS[@]}" -eq 0 ]]; then
  echo "init-performance-registry-images: пустой список репозиториев — нечего зеркалить" >&2
  exit 1
fi

if [[ "${REGISTRY_MIRROR_TSV_ONLY:-}" == 1 ]]; then
  echo "init-performance-registry-images: к зеркалированию ${#REPOS[@]} репозиториев (только TSV: ${IMAGES_LIST_FILE##*/})" >&2
else
  echo "init-performance-registry-images: к зеркалированию ${#REPOS[@]} уникальных репозиториев (TSV ∪ Java)" >&2
fi
mkdir -p "$OUT_DIR"
FAIL_LOG="$OUT_DIR/unsuccessfule_downloads.txt"
if [[ "${REGISTRY_MIRROR_APPEND_FAIL_LOG:-}" == 1 ]] && [[ -f "$FAIL_LOG" ]]; then
  {
    echo ""
    echo "# --- session $(date -u +%Y-%m-%dT%H:%M:%SZ) (incremental) ---"
    echo "# repository	reference	step	message"
  } >> "$FAIL_LOG"
else
  {
    echo "# init-performance-registry-images — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# repository	reference	step	message"
  } > "$FAIL_LOG"
fi

declare -A _mirror_ok=()

echo ">>> docker login $REG_HOST" >&2
echo "$SELECTEL_PASS" | docker login "$REG_HOST" --username "$RIID_SELECTEL_USER" --password-stdin

PULL_TIMEOUT="${REGISTRY_PULL_TIMEOUT:-12m}"

n_done=0
n_skipped=0
n_fail=0

_log_fail() {
  local repo="$1" tuse="$2" step="$3" msg="$4"
  msg="${msg//$'\t'/ }"
  msg="${msg//$'\n'/ }"
  printf '%s\t%s\t%s\t%s\n' "$repo" "$tuse" "$step" "$msg" >> "$FAIL_LOG"
}

declare -A _zstd_digest=()
n_zstd=0
n_zstd_fail=0

# Repacks an already mirrored image into zstd and pushes it to the second
# registry. The source is the gzip mirror ($mirror_dst): it was just pushed, so
# the content is guaranteed identical and no Docker Hub limits are spent.
_mirror_zstd_variant() {
  local repo="$1" tuse="$2" mirror_dst="$3" zstd_repo="$4"
  local dst="${REG_ZSTD_PREFIX}/${zstd_repo}:${tuse}"
  local digest_file step_log img
  digest_file="$(mktemp -t riid-zstd-digest-XXXXXX)"
  step_log="$(mktemp)"

  if ! img=$(podman pull -q --authfile "$ZSTD_AUTHFILE" "docker://${mirror_dst}" 2>"$step_log"); then
    _log_fail "$repo" "$tuse" "zstd-pull" "$(tail -n 1 "$step_log")"
    rm -f "$digest_file" "$step_log"
    n_zstd_fail=$((n_zstd_fail + 1))
    echo ">>> FAIL zstd-pull $mirror_dst" >&2
    return 1
  fi
  img="$(echo "$img" | tail -n 1)"

  echo ">>> zstd push $dst (level $ZSTD_LEVEL)" >&2
  if ! podman push --authfile "$ZSTD_AUTHFILE" --format oci \
        --compression-format zstd --compression-level "$ZSTD_LEVEL" \
        --force-compression --digestfile "$digest_file" \
        "$img" "docker://${dst}" 2>"$step_log"; then
    _log_fail "$repo" "$tuse" "zstd-push" "$(tail -n 1 "$step_log")"
    podman rmi -f "$img" >/dev/null 2>&1 || true
    rm -f "$digest_file" "$step_log"
    n_zstd_fail=$((n_zstd_fail + 1))
    echo ">>> FAIL zstd-push $dst" >&2
    return 1
  fi

  local zdigest
  zdigest="$(tr -d '[:space:]' < "$digest_file")"
  podman rmi -f "$img" >/dev/null 2>&1 || true
  rm -f "$digest_file" "$step_log"
  if [[ "$zdigest" != sha256:* ]]; then
    _log_fail "$repo" "$tuse" "zstd-push" "podman не вернул manifest digest"
    n_zstd_fail=$((n_zstd_fail + 1))
    return 1
  fi
  _zstd_digest["$repo"]="$zdigest"
  n_zstd=$((n_zstd + 1))
  return 0
}

for i in "${!REPOS[@]}"; do
  repo="${REPOS[i]}"
  tuse="${REPO_REFS[i]:-}"
  [[ -z "$tuse" ]] && tuse="$REF"
  if _mirror_skip_repo "$repo"; then
    echo ">>> skip: $repo (mirror skip list / нет linux/amd64 для clefos)" >&2
    n_skipped=$((n_skipped + 1))
    continue
  fi
  push_repo="$repo"
  if [[ "$REGISTRY_PUSH_REPO_STRIP_LIBRARY" == 1 ]] && [[ "$repo" == library/* ]]; then
    push_repo="${repo#library/}"
  fi
  if _is_fully_qualified_repo "$repo"; then
    src="${repo}:${tuse}"
    push_repo="${push_repo#*/}"
  else
    src="docker.io/${repo}:${tuse}"
  fi
  dst="${REG_PREFIX}/${push_repo}:${tuse}"
  echo ">>> pull  $src (timeout ${PULL_TIMEOUT})" >&2
  _step_log="$(mktemp)"
  set +e
  timeout "$PULL_TIMEOUT" docker pull "$src" 2>&1 | tee "$_step_log"
  pull_ec=${PIPESTATUS[0]}
  set -e
  if [[ "$pull_ec" -ne 0 ]]; then
    if [[ "$pull_ec" -eq 124 ]]; then
      _log_fail "$repo" "$tuse" "pull" "timeout (${PULL_TIMEOUT})"
    else
      _log_fail "$repo" "$tuse" "pull" "$(tail -n 1 "$_step_log")"
    fi
    rm -f "$_step_log"
    n_fail=$((n_fail + 1))
    echo ">>> FAIL pull $src (см. $FAIL_LOG)" >&2
    continue
  fi
  rm -f "$_step_log"
  echo ">>> tag+push $dst" >&2
  if ! tag_out=$(docker tag "$src" "$dst" 2>&1); then
    _log_fail "$repo" "$tuse" "tag" "$(echo "$tag_out" | tail -n 1)"
    n_fail=$((n_fail + 1))
    echo ">>> FAIL tag $src -> $dst" >&2
    continue
  fi
  if ! docker push "$dst" 2>&1 | tee "$_step_log"; then
    _log_fail "$repo" "$tuse" "push" "$(tail -n 1 "$_step_log")"
    rm -f "$_step_log"
    n_fail=$((n_fail + 1))
    echo ">>> FAIL push $dst" >&2
    continue
  fi
  rm -f "$_step_log"
  _mirror_ok["$repo"]=1
  n_done=$((n_done + 1))
  if [[ "$MIRROR_ZSTD" == 1 ]]; then
    _mirror_zstd_variant "$repo" "$tuse" "$dst" "$push_repo" || true
  fi
done

emit_performance_registry_smoke_map_rows() {
  local i repo push_repo riid_repo tuse
  for i in "${!REPOS[@]}"; do
    repo="${REPOS[i]}"
    tuse="${REPO_REFS[i]:-}"
    [[ -z "$tuse" ]] && tuse="$REF"
    if _mirror_skip_repo "$repo"; then
      continue
    fi
    [[ -z "${_mirror_ok[$repo]:-}" ]] && continue
    push_repo="$repo"
    if [[ "$REGISTRY_PUSH_REPO_STRIP_LIBRARY" == 1 ]] && [[ "$repo" == library/* ]]; then
      push_repo="${repo#library/}"
    fi
    if _is_fully_qualified_repo "$repo"; then
      push_repo="${push_repo#*/}"
    fi
    if [[ -n "$REG_REPO_PREFIX" ]]; then
      riid_repo="${REG_REPO_PREFIX}/${push_repo}"
    else
      riid_repo="$push_repo"
    fi
    printf '%s\t%s\t%s\n' "$repo" "$riid_repo" "$tuse"
  done
}

emit_performance_registry_smoke_map() {
  echo "# Generated by init-performance-registry-images.sh ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
  echo "# tag (POPULAR_IMAGES_REFERENCE): $REF"
  echo "# REG_HOST=$REG_HOST  REG_PREFIX=$REG_PREFIX"
  if [[ "${REGISTRY_MIRROR_TSV_ONLY:-}" == 1 ]]; then
    echo "# merge: ${IMAGES_LIST_FILE##*/} only (REGISTRY_MIRROR_TSV_ONLY); skip: clefos + REGISTRY_MIRROR_SKIP"
  else
    echo "# merge: ${IMAGES_LIST_FILE##*/} ∪ Java library/*; skip: clefos + REGISTRY_MIRROR_SKIP; в таблице только успешно зеркалированные (см. out/unsuccessfule_downloads.txt)"
  fi
  echo "# Columns: docker_hub_repository<TAB>riid_registry_path<TAB>reference (tag on mirror)"
  emit_performance_registry_smoke_map_rows
}

MAP_OUT="$OUT_DIR/performance-registry-smoke-map.tsv"
if [[ "${REGISTRY_MIRROR_APPEND_SMOKE_MAP:-}" == 1 ]] && [[ -f "$MAP_OUT" ]]; then
  emit_performance_registry_smoke_map_rows >> "$MAP_OUT"
else
  emit_performance_registry_smoke_map > "$MAP_OUT"
fi
echo ">>> smoke map (Docker Hub name to RIID repository on mirror): $MAP_OUT" >&2

emit_zstd_map_rows() {
  local i repo push_repo zstd_repo tuse
  for i in "${!REPOS[@]}"; do
    repo="${REPOS[i]}"
    tuse="${REPO_REFS[i]:-}"
    [[ -z "$tuse" ]] && tuse="$REF"
    [[ -z "${_zstd_digest[$repo]:-}" ]] && continue
    push_repo="$repo"
    if [[ "$REGISTRY_PUSH_REPO_STRIP_LIBRARY" == 1 ]] && [[ "$repo" == library/* ]]; then
      push_repo="${repo#library/}"
    fi
    if _is_fully_qualified_repo "$repo"; then
      push_repo="${push_repo#*/}"
    fi
    zstd_repo="${REG_ZSTD_REPO_PREFIX}/${push_repo}"
    printf '%s\t%s\t%s\t%s\n' "$repo" "$zstd_repo" "$tuse" "${_zstd_digest[$repo]}"
  done
}

if [[ "$MIRROR_ZSTD" == 1 ]]; then
  ZSTD_MAP_OUT="$OUT_DIR/performance-registry-zstd-map.tsv"
  if [[ "${REGISTRY_MIRROR_APPEND_SMOKE_MAP:-}" == 1 ]] && [[ -f "$ZSTD_MAP_OUT" ]]; then
    emit_zstd_map_rows >> "$ZSTD_MAP_OUT"
  else
    {
      echo "# Generated by init-performance-registry-images.sh ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
      echo "# zstd mirror: REG_ZSTD_PREFIX=$REG_ZSTD_PREFIX  level=$ZSTD_LEVEL  (podman --compression-format zstd --force-compression)"
      echo "# Columns: docker_hub_repository<TAB>zstd_registry_path<TAB>reference<TAB>zstd_manifest_digest"
      emit_zstd_map_rows
    } > "$ZSTD_MAP_OUT"
  fi
  echo ">>> zstd map: $ZSTD_MAP_OUT" >&2
fi

echo ">>> готово: $n_done образов в $REG_PREFIX (в списке ${#REPOS[@]}, пропущено зеркалированием $n_skipped, ошибок $n_fail)" >&2
if [[ "$MIRROR_ZSTD" == 1 ]]; then
  echo ">>> zstd: $n_zstd образов в $REG_ZSTD_PREFIX, ошибок $n_zstd_fail" >&2
fi
[[ "$n_fail" -eq 0 && "${n_zstd_fail:-0}" -eq 0 ]] || echo ">>> журнал ошибок зеркала: $FAIL_LOG" >&2
