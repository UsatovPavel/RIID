#!/usr/bin/env bash
# terraform destroy leaks two kinds of volume: PVC-backed ones (Kubernetes CSI
# creates them, not terraform - 3x 8 GB found 2026-09-08) and MKS node boot
# disks (100 GB each, not cleaned by nodegroup delete either). Both are just
# volumes in the project, so list-all/delete-all covers both. CONFIRM=yes to delete.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$(cd "$HERE/../../../../config" && pwd)/.env}"
AUTH_URL="${SELECTEL_AUTH_URL:-https://cloud.api.selcloud.ru/identity/v3}"
REGION="${SELECTEL_REGION:-ru-3}"

# shellcheck source=load-env.inc.sh
. "$HERE/load-env.inc.sh"
riid_load_env "$ENV_FILE" || {
  echo "purge-account-volumes: $ENV_FILE not found" >&2
  exit 1
}

project_id="${SELECTEL_PROJECT_ID:-}"
if [[ -z "$project_id" ]]; then
  project_id="$("$HERE/resolve-project-id.sh")" || {
    echo "purge-account-volumes: could not resolve a project id" >&2
    exit 1
  }
fi

for var in SELECTEL_ACCOUNT_ID SELECTEL_IAM_USER SELECTEL_IAM_PASSWORD; do
  [[ -n "${!var:-}" ]] || { echo "purge-account-volumes: $var is not set in $ENV_FILE" >&2; exit 1; }
done

# A domain-scoped token (same shape resolve-project-id.sh falls back to) works
# here because it is only ever used project-scoped below, via the id above -
# no separate password auth needed against the project name/domain pairing.
scoped_req="$(SELECTEL_IAM_USER="$SELECTEL_IAM_USER" SELECTEL_ACCOUNT_ID="$SELECTEL_ACCOUNT_ID" \
  SELECTEL_IAM_PASSWORD="$SELECTEL_IAM_PASSWORD" PROJECT_ID="$project_id" python3 -c '
import json, os
print(json.dumps({"auth": {"identity": {"methods": ["password"], "password": {"user": {
    "name": os.environ["SELECTEL_IAM_USER"],
    "domain": {"name": os.environ["SELECTEL_ACCOUNT_ID"]},
    "password": os.environ["SELECTEL_IAM_PASSWORD"]}}},
  "scope": {"project": {"id": os.environ["PROJECT_ID"]}}}}))')"

auth_resp="$(mktemp)"
trap 'rm -f "$auth_resp"' EXIT

token="$(curl -sS -o "$auth_resp" -D - -X POST "$AUTH_URL/auth/tokens" \
  -H 'Content-Type: application/json' -d "$scoped_req" \
  | awk 'BEGIN{IGNORECASE=1} /^x-subject-token:/{print $2}' | tr -d '\r')"

if [[ -z "$token" ]]; then
  echo "purge-account-volumes: Keystone did not return a project-scoped token; response:" >&2
  cat "$auth_resp" >&2
  exit 1
fi

vol_resp="$(mktemp)"
trap 'rm -f "$auth_resp" "$vol_resp"' EXIT

code="$(curl -sS -o "$vol_resp" -w '%{http_code}' -H "X-Auth-Token: $token" \
  "https://${REGION}.cloud.api.selcloud.ru/volume/v3/${project_id}/volumes/detail?all_tenants=1")"
if [[ "$code" != "200" ]]; then
  echo "purge-account-volumes: volume list failed (HTTP $code)" >&2
  cat "$vol_resp" >&2
  exit 1
fi

mapfile -t ids < <(VOL_RESP="$vol_resp" python3 -c '
import json, os
d = json.load(open(os.environ["VOL_RESP"]))
for v in d.get("volumes", []):
    vid, name, size, status, created = v["id"], v.get("name") or "-", v.get("size"), v.get("status"), v.get("created_at")
    print(f"{vid}\t{name}\t{size}GB\t{status}\t{created}")
')

if [[ "${#ids[@]}" -eq 0 ]]; then
  echo "purge-account-volumes: no volumes in project $project_id"
  exit 0
fi

echo "purge-account-volumes: ${#ids[@]} volume(s) in project $project_id:"
printf '  %s\n' "${ids[@]}"

if [[ "${CONFIRM:-}" != "yes" ]]; then
  echo "purge-account-volumes: dry run (list only) - set CONFIRM=yes to delete these"
  exit 0
fi

fail=0
for row in "${ids[@]}"; do
  id="${row%%$'\t'*}"
  status="$(cut -f4 <<<"$row")"
  if [[ "$status" == "in-use" ]]; then
    echo "purge-account-volumes: SKIP $id (in-use - attached, not an orphan)"
    continue
  fi
  del_code="$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE -H "X-Auth-Token: $token" \
    "https://${REGION}.cloud.api.selcloud.ru/volume/v3/${project_id}/volumes/${id}")"
  if [[ "$del_code" == "202" || "$del_code" == "204" ]]; then
    echo "purge-account-volumes: deleted $id"
  else
    echo "purge-account-volumes: FAILED to delete $id (HTTP $del_code)" >&2
    fail=1
  fi
done
exit "$fail"
