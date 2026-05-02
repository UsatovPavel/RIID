#!/usr/bin/env bash
# Зеркалирует образы в приватный реестр для performance/smoke сценариев.
#
# Список имён — объединение без дубликатов (A ∪ B):
#   A — строки images_list.sizes.tsv: колонка repository + колонка tag (если tag пустой — берётся POPULAR_IMAGES_REFERENCE из Java).
#   B — репозитории из PopularDockerHubImagesFromProgramDocs.java; только те, которых ещё нет в A; тег для них — POPULAR_IMAGES_REFERENCE.
# Повторные строки заголовка (repository<TAB>tag) пропускаются.
#
# REGISTRY в .env:
#   - только ID реестра Selectel: 2d731864-81c8-...  → push в cr.selcloud.ru/<ID>/...
#   - полный префикс: cr.selcloud.ru/<ID> или другой.host/проект
# Опционально REGISTRY_LOGIN_HOST, если без «/» указан не Selectel (по умолчанию cr.selcloud.ru).
#
# Selectel: в доке лимит длины имени образа; пути вида library/hello-seattle (21 символ) реестр отклоняет
# (docker push после ретраев: unknown:). Для *selcloud.ru по умолчанию push идёт без префикса library/:
#   cr.selcloud.ru/<id>/hello-seattle:latest
# После успешного зеркала пишется performance-registry-smoke-map.tsv: docker_hub_repository<TAB>riid_repository<TAB>reference (тег зеркала).
# Смок Selectel: SMOKE_REFERENCE должен совпадать с reference для выбранного образа (колонка 3 в map или тег из TSV).
# Отключить strip library/: REGISTRY_PUSH_REPO_STRIP_LIBRARY=0
#
# Неудачные pull/tag/push пишутся в unsuccessfule_downloads.txt (рядом со скриптом), цикл не прерывается.
# В performance-registry-smoke-map.tsv попадают только успешно зеркалированные репы.
#
# Режим только списка (без ∪ Java): REGISTRY_MIRROR_TSV_ONLY=1 — для инкрементального зеркала по урезанному TSV.
# Дописать строки в smoke-map без перезатирания: REGISTRY_MIRROR_APPEND_SMOKE_MAP=1.
# Дописать блок в unsuccessfule_downloads.txt: REGISTRY_MIRROR_APPEND_FAIL_LOG=1.
#
# library/clefos: на Docker Hub нет манифеста linux/amd64 — в скрипте пропуск. Доп. пропуски: REGISTRY_MIRROR_SKIP="ns/repo ns/repo2"
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DIR/../../../.." && pwd)"
ENV_FILE="${1:-$DIR/../.env}"
JAVA_LIST="$REPO_ROOT/src/testFixtures/java/riid/config/PopularDockerHubImagesFromProgramDocs.java"
IMAGES_LIST_FILE="${RIID_IMAGES_LIST:-$DIR/images_list.sizes.tsv}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "init-performance-registry-images: нет $ENV_FILE (задайте путь: $0 /path/.env)" >&2
  exit 1
fi
if [[ ! -f "$JAVA_LIST" ]]; then
  echo "init-performance-registry-images: не найден $JAVA_LIST" >&2
  exit 1
fi
if [[ ! -f "$IMAGES_LIST_FILE" ]]; then
  echo "init-performance-registry-images: не найден $IMAGES_LIST_FILE (задайте RIID_IMAGES_LIST или положите images_list.sizes.tsv рядом со скриптом)" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${REGISTRY_USER:?в $ENV_FILE должен быть REGISTRY_USER}"
: "${REGISTRY_PASSWORD:?в $ENV_FILE должен быть REGISTRY_PASSWORD}"
: "${REGISTRY:?в $ENV_FILE должен быть REGISTRY (см. комментарий в шапке скрипта)}"

REGISTRY="${REGISTRY%/}"
# Полный префикс push: cr.selcloud.ru/<id> или свой host/проект.
# Только UUID/имя без «/» (как в панели Selectel) → подставляем cr.selcloud.ru/
if [[ "$REGISTRY" == */* ]]; then
  REG_PREFIX="$REGISTRY"
  REG_HOST="${REGISTRY%%/*}"
else
  REG_HOST="${REGISTRY_LOGIN_HOST:-cr.selcloud.ru}"
  REG_PREFIX="${REG_HOST}/${REGISTRY}"
fi
# Путь репозитория под REG_HOST (для RIID POST /pull при профиле registry-selectel): <id>/jobber и т.д.
_host_slash="${REG_HOST}/"
REG_REPO_PREFIX="${REG_PREFIX#"$_host_slash"}"

if [[ -z "${REGISTRY_PUSH_REPO_STRIP_LIBRARY:-}" ]]; then
  if [[ "$REG_HOST" == *selcloud.ru ]]; then
    REGISTRY_PUSH_REPO_STRIP_LIBRARY=1
  else
    REGISTRY_PUSH_REPO_STRIP_LIBRARY=0
  fi
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
FAIL_LOG="$DIR/unsuccessfule_downloads.txt"
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
echo "$REGISTRY_PASSWORD" | docker login "$REG_HOST" --username "$REGISTRY_USER" --password-stdin

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
  src="docker.io/${repo}:${tuse}"
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
    echo "# merge: ${IMAGES_LIST_FILE##*/} ∪ Java library/*; skip: clefos + REGISTRY_MIRROR_SKIP; в таблице только успешно зеркалированные (см. unsuccessfule_downloads.txt)"
  fi
  echo "# Columns: docker_hub_repository<TAB>riid_registry_path<TAB>reference (tag on mirror)"
  emit_performance_registry_smoke_map_rows
}

MAP_OUT="$DIR/performance-registry-smoke-map.tsv"
if [[ "${REGISTRY_MIRROR_APPEND_SMOKE_MAP:-}" == 1 ]] && [[ -f "$MAP_OUT" ]]; then
  emit_performance_registry_smoke_map_rows >> "$MAP_OUT"
else
  emit_performance_registry_smoke_map > "$MAP_OUT"
fi
echo ">>> smoke map (Docker Hub name to RIID repository on mirror): $MAP_OUT" >&2

echo ">>> готово: $n_done образов в $REG_PREFIX (в списке ${#REPOS[@]}, пропущено зеркалированием $n_skipped, ошибок $n_fail)" >&2
[[ "$n_fail" -eq 0 ]] || echo ">>> журнал ошибок зеркала: $FAIL_LOG" >&2
