#!/usr/bin/env bash
# Sets runtime.prefixImport in RIID's cluster config and proves the pods took it.
# Usage: set-prefix-import.sh true|false
#
# Two sources, and the Secret wins: daemonset.yaml's init container copies
# /sec/config.yaml when the Secret exists and falls back to the ConfigMap only
# when it does not. render-riid-config-secret.sh builds that Secret from the very
# same configmap.yaml, so both carry the key and both are patched here.
#
# Env:
#   RIID_NAMESPACE   - default: riid-system
#   RIID_CONTAINER   - default: riid
#   ROLLOUT_TIMEOUT  - default: 300s
set -euo pipefail

VALUE="${1:?usage: set-prefix-import.sh true|false}"
case "$VALUE" in true|false) ;; *) echo "set-prefix-import: expected true|false, got '$VALUE'" >&2; exit 2;; esac

NS="${RIID_NAMESPACE:-riid-system}"
CONTAINER="${RIID_CONTAINER:-riid}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-300s}"
EFFECTIVE_CONFIG="/var/lib/riid/work/config.yaml"

command -v yq >/dev/null || { echo "set-prefix-import: yq (v4) required" >&2; exit 1; }

patch_config_yaml() {
  local kind="$1" name="$2" decode="$3" cur new
  kubectl -n "$NS" get "$kind" "$name" >/dev/null 2>&1 || return 1
  cur="$(kubectl -n "$NS" get "$kind" "$name" -o jsonpath='{.data.config\.yaml}')"
  [ -n "$cur" ] || { echo "set-prefix-import: $kind/$name has no config.yaml" >&2; return 1; }
  if [ "$decode" = 1 ]; then cur="$(printf '%s' "$cur" | base64 -d)"; fi
  new="$(printf '%s' "$cur" | VAL="$VALUE" yq e '.runtime.prefixImport = (strenv(VAL) == "true")' -)"
  kubectl -n "$NS" patch "$kind" "$name" --type merge \
    -p "$(VAL="$new" yq -n -o=json '.data["config.yaml"] = strenv(VAL)')" >/dev/null
  echo "set-prefix-import: patched $kind/$name -> prefixImport=$VALUE"
}

patched=0
patch_config_yaml configmap riid-config 0 && patched=1
# The Secret shadows the ConfigMap, so leaving it stale would silently keep the
# old mode - exactly the failure validate-arm.sh exists to catch.
patch_config_yaml secret riid-config-secret 1 && patched=1
[ "$patched" = 1 ] || { echo "set-prefix-import: neither ConfigMap nor Secret found in $NS" >&2; exit 1; }

echo "set-prefix-import: restarting daemonset/riid (config is read once at startup)"
kubectl -n "$NS" rollout restart daemonset/riid >/dev/null
kubectl -n "$NS" rollout status daemonset/riid --timeout="$ROLLOUT_TIMEOUT"

# Reading the file the app actually loads, not the object we patched: this is the
# only check that survives the Secret/ConfigMap precedence and a stale rollout.
fail=0
for pod in $(kubectl -n "$NS" get pods -l app.kubernetes.io/name=riid \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'); do
  [ -n "$pod" ] || continue
  got="$(kubectl -n "$NS" exec -c "$CONTAINER" "$pod" -- \
    sh -c "sed -n '/^runtime:/,/^[a-z]/p' '$EFFECTIVE_CONFIG' | sed -n 's/.*prefixImport:[[:space:]]*//p' | head -1" 2>/dev/null | tr -d '[:space:]')"
  if [ "$got" != "$VALUE" ]; then
    echo "set-prefix-import: FAILED $pod has prefixImport='${got:-<none>}', expected '$VALUE'" >&2
    fail=1
  fi
done
if [ "$fail" != 0 ]; then
  echo "set-prefix-import: pods did not take the new value - do not run the arm" >&2
  exit 1
fi
echo "set-prefix-import: every riid pod reports prefixImport=$VALUE"
