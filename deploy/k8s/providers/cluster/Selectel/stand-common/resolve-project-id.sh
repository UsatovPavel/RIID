#!/usr/bin/env bash
# Resolves a VPC project name into the 32-hex project id required by the
# selectel provider. Prints the id to stdout; everything else goes to stderr.
set -euo pipefail

ENV_FILE="${ENV_FILE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../config" && pwd)/.env}"
AUTH_URL="${SELECTEL_AUTH_URL:-https://cloud.api.selcloud.ru/identity/v3}"

# shellcheck source=load-env.inc.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/load-env.inc.sh"
riid_load_env "$ENV_FILE" || true

if [[ -n "${SELECTEL_PROJECT_ID:-}" ]]; then
  printf '%s\n' "$SELECTEL_PROJECT_ID"
  exit 0
fi

# A panel static token cannot manage OpenStack objects, but it does read the
# project list, which is all we need here and works when Keystone refuses a
# domain-scoped token to a service user.
if [[ -n "${SELECTEL_API_TOKEN:-}" && -n "${SELECTEL_IAM_PROJECT:-}" ]]; then
  if project_id="$(curl -sS -m 30 -H "X-Token: $SELECTEL_API_TOKEN" \
      https://api.selectel.ru/vpc/resell/v2/projects \
      | SELECTEL_IAM_PROJECT="$SELECTEL_IAM_PROJECT" python3 -c '
import json, os, sys
want = os.environ["SELECTEL_IAM_PROJECT"]
for p in json.load(sys.stdin).get("projects", []):
    if p["name"] == want or p["id"] == want:
        print(p["id"])
        break
')" && [[ -n "$project_id" ]]; then
    printf '%s\n' "$project_id"
    exit 0
  fi
fi

for var in SELECTEL_ACCOUNT_ID SELECTEL_IAM_USER SELECTEL_IAM_PASSWORD SELECTEL_IAM_PROJECT; do
  [[ -n "${!var:-}" ]] || { echo "$var is not set in $ENV_FILE" >&2; exit 1; }
done

request='{"auth":{"identity":{"methods":["password"],"password":{"user":{"name":"'"$SELECTEL_IAM_USER"'","domain":{"name":"'"$SELECTEL_ACCOUNT_ID"'"},"password":"'"$SELECTEL_IAM_PASSWORD"'"}}},"scope":{"domain":{"name":"'"$SELECTEL_ACCOUNT_ID"'"}}}}'

response="$(mktemp)"
trap 'rm -f "$response"' EXIT

token="$(curl -sS -o "$response" -D - -X POST "$AUTH_URL/auth/tokens" \
  -H 'Content-Type: application/json' -d "$request" \
  | awk 'BEGIN{IGNORECASE=1} /^x-subject-token:/{print $2}' | tr -d '\r')"

# Selectel service users are usually confined to a project, so a domain-scoped
# token is refused. A project-scoped one already carries the id we are after.
if [[ -z "$token" ]]; then
  scoped='{"auth":{"identity":{"methods":["password"],"password":{"user":{"name":"'"$SELECTEL_IAM_USER"'","domain":{"name":"'"$SELECTEL_ACCOUNT_ID"'"},"password":"'"$SELECTEL_IAM_PASSWORD"'"}}},"scope":{"project":{"name":"'"$SELECTEL_IAM_PROJECT"'","domain":{"name":"'"$SELECTEL_ACCOUNT_ID"'"}}}}}'
  if curl -sS -o "$response" -X POST "$AUTH_URL/auth/tokens" \
       -H 'Content-Type: application/json' -d "$scoped" \
     && project_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["token"]["project"]["id"])' < "$response" 2>/dev/null)" \
     && [[ -n "$project_id" ]]; then
    printf '%s\n' "$project_id"
    exit 0
  fi
fi

if [[ -z "$token" ]]; then
  {
    echo "Keystone did not return a token for service user '$SELECTEL_IAM_USER'."
    echo "A service user may be denied a domain-scoped token; in that case take the"
    echo "project id from the control panel (project -> settings -> ID) and put it into"
    echo "$ENV_FILE as SELECTEL_PROJECT_ID."
  } >&2
  exit 1
fi

curl -sS "$AUTH_URL/projects" -H "X-Auth-Token: $token" \
  | python3 -c '
import json, os, sys
want = os.environ["SELECTEL_IAM_PROJECT"]
projects = json.load(sys.stdin).get("projects", [])
for p in projects:
    if p["name"] == want or p["id"] == want:
        print(p["id"])
        sys.exit(0)
print("project %r not found; available: %s" % (want, ", ".join(p["name"] for p in projects)), file=sys.stderr)
sys.exit(1)
'
