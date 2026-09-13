#!/usr/bin/env bash
# Deletes the Cinder volumes MKS leaves behind: node boot disks, and PVC-backed
# volumes whose cluster is already gone. Scoped to the stand named by
# STAND_PREFIX - every other volume in the project is listed and left alone.
# CONFIRM=yes to delete, WAIT_DETACH=<seconds> to wait for nodes to release them,
# WAIT_CLUSTER_GONE=<seconds> to wait for MKS to stop listing the destroyed cluster.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-$(cd "$HERE/../../../../config" && pwd)/.env}"
AUTH_URL="${SELECTEL_AUTH_URL:-https://cloud.api.selcloud.ru/identity/v3}"
REGION="${SELECTEL_REGION:-ru-3}"
WAIT_DETACH="${WAIT_DETACH:-0}"
WAIT_CLUSTER_GONE="${WAIT_CLUSTER_GONE:-0}"
SELF="purge-stand-volumes"

STAND_PREFIX="${STAND_PREFIX:-}"
if [[ -z "$STAND_PREFIX" ]]; then
  echo "$SELF: STAND_PREFIX is not set; pass the stand's cluster_name (e.g. riid-bench)" >&2
  exit 1
fi

# shellcheck source=load-env.inc.sh
. "$HERE/load-env.inc.sh"
riid_load_env "$ENV_FILE" || {
  echo "$SELF: $ENV_FILE not found" >&2
  exit 1
}

project_id="${SELECTEL_PROJECT_ID:-}"
if [[ -z "$project_id" ]]; then
  project_id="$("$HERE/resolve-project-id.sh")" || {
    echo "$SELF: could not resolve a project id" >&2
    exit 1
  }
fi

for var in SELECTEL_ACCOUNT_ID SELECTEL_IAM_USER SELECTEL_IAM_PASSWORD; do
  [[ -n "${!var:-}" ]] || { echo "$SELF: $var is not set in $ENV_FILE" >&2; exit 1; }
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
mks_resp="$(mktemp)"
vol_resp="$(mktemp)"
trap 'rm -f "$auth_resp" "$mks_resp" "$vol_resp"' EXIT

token="$(curl -sS -o "$auth_resp" -D - -X POST "$AUTH_URL/auth/tokens" \
  -H 'Content-Type: application/json' -d "$scoped_req" \
  | awk 'BEGIN{IGNORECASE=1} /^x-subject-token:/{print $2}' | tr -d '\r')"

if [[ -z "$token" ]]; then
  echo "$SELF: Keystone did not return a project-scoped token; response:" >&2
  cat "$auth_resp" >&2
  exit 1
fi

# A pvc-* volume carries no cluster id (Cinder CSI writes the literal "kubernetes"),
# so it can only be called an orphan once no MKS cluster is left to own it. An
# unreadable cluster list counts as "a cluster may be alive", never as "none".
csi_eligible() {
  local code names quiet="${1:-}"
  code="$(curl -sS -o "$mks_resp" -w '%{http_code}' -H "X-Auth-Token: $token" \
    "https://${REGION}.mks.selcloud.ru/v1/clusters")"
  if [[ "$code" != "200" ]]; then
    [[ -n "$quiet" ]] || echo "$SELF: MKS cluster list unreadable (HTTP $code) - CSI volumes are kept" >&2
    return 1
  fi
  names="$(MKS_RESP="$mks_resp" python3 -c '
import json, os
print(" ".join(c.get("name", "?") for c in json.load(open(os.environ["MKS_RESP"])).get("clusters", [])))')"
  [[ -z "$names" ]] && return 0
  [[ -n "$quiet" ]] || echo "$SELF: MKS cluster(s) still present: $names - CSI volumes are kept"
  return 1
}

# MKS keeps listing a cluster for minutes after terraform reports it destroyed,
# and csi_eligible reads that as "a cluster may be alive" and keeps every CSI
# volume. Without this wait a purge right after destroy exits having freed
# nothing - measured 2026-09-10: 354GB left behind, released only by Selectel.
wait_cluster_gone() {
  [[ "$WAIT_CLUSTER_GONE" -gt 0 ]] || return 0
  local deadline=$(( $(date +%s) + WAIT_CLUSTER_GONE ))
  csi_eligible quiet && return 0
  echo "$SELF: MKS still lists a cluster; waiting up to ${WAIT_CLUSTER_GONE}s for it to disappear"
  while [[ "$(date +%s)" -lt "$deadline" ]]; do
    sleep 15
    csi_eligible quiet && { echo "$SELF: MKS list is empty now"; return 0; }
  done
  echo "$SELF: still listed after ${WAIT_CLUSTER_GONE}s - CSI volumes will be kept" >&2
  return 1
}

# Three outcomes per volume: DELETE (this stand's, detached), KEEP (this stand's
# but not deletable yet) and FOREIGN (belongs to something else in the project).
classify() {
  local code csi_ok=no
  csi_eligible && csi_ok=yes
  code="$(curl -sS -o "$vol_resp" -w '%{http_code}' -H "X-Auth-Token: $token" \
    "https://${REGION}.cloud.api.selcloud.ru/volume/v3/${project_id}/volumes/detail")"
  if [[ "$code" != "200" ]]; then
    echo "$SELF: volume list failed (HTTP $code)" >&2
    cat "$vol_resp" >&2
    return 1
  fi
  mapfile -t rows < <(VOL_RESP="$vol_resp" STAND_PREFIX="$STAND_PREFIX" CSI_ELIGIBLE="$csi_ok" python3 -c '
import json, os
prefix = os.environ["STAND_PREFIX"]
csi_ok = os.environ["CSI_ELIGIBLE"] == "yes"
for v in json.load(open(os.environ["VOL_RESP"])).get("volumes", []):
    vid, name = v["id"], v.get("name") or ""
    size, status = v.get("size"), v.get("status")
    meta = v.get("metadata") or {}
    ns = meta.get("csi.storage.k8s.io/pvc/namespace", "")
    pvc = meta.get("csi.storage.k8s.io/pvc/name", "")
    csi = "cinder.csi.openstack.org/cluster" in meta and name.startswith("pvc-")
    if name.startswith(prefix + "-"):
        outcome, why = "DELETE", "node boot disk of " + prefix
    elif csi and csi_ok:
        outcome, why = "DELETE", f"CSI volume of {ns}/{pvc}"
    elif csi:
        outcome, why = "KEEP", f"CSI volume of {ns}/{pvc}, cluster still alive"
    else:
        outcome, why = "FOREIGN", "not created by " + prefix
    if outcome == "DELETE" and status == "in-use":
        outcome, why = "ATTACHED", "attached, not an orphan yet"
    print(f"{outcome}\t{vid}\t{name or chr(45)}\t{size}GB\t{status}\t{why}")
')
}

count_of() {
  printf '%s\n' "${rows[@]}" | awk -F'\t' -v want="$1" '$1 == want' | wc -l
}

show() {
  local want="$1" label="$2" found=0 row
  for row in "${rows[@]}"; do
    [[ "${row%%$'\t'*}" == "$want" ]] || continue
    [[ "$found" == 0 ]] && echo "$label"
    found=1
    printf '  %s\n' "$(cut -f2- <<<"$row" | tr '\t' ' ')"
  done
}

# Right after the node group is deleted the boot disks are still attached to
# servers Nova is only now tearing down, and Cinder answers 400 for those.
rows=()
wait_cluster_gone || true
classify
deadline=$(( $(date +%s) + WAIT_DETACH ))
while [[ "$(count_of ATTACHED)" -gt 0 && "$(date +%s)" -lt "$deadline" ]]; do
  echo "$SELF: $(count_of ATTACHED) volume(s) still attached, waiting"
  sleep 15
  classify
done

echo "$SELF: ${#rows[@]} volume(s) in project $project_id, stand prefix '$STAND_PREFIX'"
show FOREIGN  "$SELF: outside this stand, never touched:"
show KEEP     "$SELF: this stand's, kept for now:"
show ATTACHED "$SELF: this stand's, still attached:"
show DELETE   "$SELF: orphans of this stand, deletable:"

mapfile -t doomed < <(printf '%s\n' "${rows[@]}" | awk -F'\t' '$1 == "DELETE" {print $2}')
if [[ "${#doomed[@]}" -eq 0 ]]; then
  echo "$SELF: nothing to delete"
  exit 0
fi

if [[ "${CONFIRM:-}" != "yes" ]]; then
  echo "$SELF: dry run (list only) - set CONFIRM=yes to delete the ${#doomed[@]} orphan(s) above"
  exit 0
fi

fail=0
for id in "${doomed[@]}"; do
  del_code="$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE -H "X-Auth-Token: $token" \
    "https://${REGION}.cloud.api.selcloud.ru/volume/v3/${project_id}/volumes/${id}")"
  if [[ "$del_code" == "202" || "$del_code" == "204" ]]; then
    echo "$SELF: deleted $id"
  else
    echo "$SELF: FAILED to delete $id (HTTP $del_code)" >&2
    fail=1
  fi
done
exit "$fail"
