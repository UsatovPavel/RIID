# Reads deploy/k8s/config/.env literally.
# `set -a; . .env` cannot be used here: bash expands $, ` and \ inside the values,
# which silently truncates passwords that contain them.
# Usage: ENV_FILE=... source load-env.inc.sh

riid_load_env() {
  local env_file="$1" line key value
  [[ -f "$env_file" ]] || return 1

  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" == *=* ]] || continue

    key="${line%%=*}"
    key="${key#"${key%%[![:space:]]*}"}"
    key="${key%"${key##*[![:space:]]}"}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue

    value="${line#*=}"
    if [[ "$value" == \"*\" && ${#value} -ge 2 ]]; then
      value="${value:1:${#value}-2}"
    elif [[ "$value" == \'*\' && ${#value} -ge 2 ]]; then
      value="${value:1:${#value}-2}"
    fi

    export "$key=$value"
  done < "$env_file"
}
