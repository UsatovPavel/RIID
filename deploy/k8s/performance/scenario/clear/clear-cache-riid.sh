#!/usr/bin/env bash
# RIID's own cache only: the OCI scratch it builds before handing an image to an
# engine. Not the engine store (clear-cache-engines.sh) and not Dragonfly
# (clear-cache-dragonfly.sh) - RIID writes into all three and each is cleared by
# its own script so an arm can wipe exactly what it used.
#
# Two directories, both hostPath: app.tempDirectory (RIID_WORK_DIR) holds the
# oci-layout-* staging trees, and the pod's /tmp - mounted from the node's
# /var/lib/riid/tmp - holds riid-cache-tmp-*, which is where the bulk goes: 12 GB
# per node mid-arm on 2026-09-11. Both are measured, in bytes, not by name.
#
# Env:
#   RIID_NAMESPACE       - default: riid-system
#   RIID_CONTAINER       - default: riid
#   RIID_LABEL_SELECTOR  - default: app.kubernetes.io/name=riid
#   RIID_WORK_DIR        - default: /var/lib/riid/work (app.tempDirectory)
set -euo pipefail

NS="${RIID_NAMESPACE:-riid-system}"
CONTAINER="${RIID_CONTAINER:-riid}"
LABEL="${RIID_LABEL_SELECTOR:-app.kubernetes.io/name=riid}"
WORK_DIR="${RIID_WORK_DIR:-/var/lib/riid/work}"

. "$(dirname "${BASH_SOURCE[0]}")/kubectl-retry.inc.sh"

if [[ -n "${KUBECONFIG:-}" && ! -f "$KUBECONFIG" ]]; then
  echo "clear-cache-riid: kubeconfig not found: $KUBECONFIG" >&2
  exit 1
fi

mapfile -t pods < <(riid_kc -n "$NS" get pods -l "$LABEL" \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')

if ((${#pods[@]} == 0)); then
  echo "clear-cache-riid: no pods with label $LABEL in $NS" >&2
  exit 1
fi

failed=0
cleaned=0

for pod in "${pods[@]}"; do
  [[ -z "$pod" ]] && continue
  phase=$(riid_kc -n "$NS" get pod "$pod" -o jsonpath='{.status.phase}')
  if [[ "$phase" != Running ]]; then
    echo ">>> skip $pod (phase=$phase)" >&2
    continue
  fi
  if riid_kc -n "$NS" exec -c "$CONTAINER" "$pod" -- env RIID_WORK_DIR="$WORK_DIR" sh -ec '
      # oci-layout-* is the staging tree RIID hands to the engine. A killed arm
      # leaves one behind: 4.4 GiB per node went unnoticed on 2026-09-11 because
      # the cleanup matched three names and this was not one of them.
      for d in "$RIID_WORK_DIR" /tmp; do
        [ -d "$d" ] || continue
        find "$d" -maxdepth 1 -type d -name "riid-cache-tmp-*" -exec rm -rf {} + 2>/dev/null
        find "$d" -maxdepth 1 -type d -name "riid-prefix-*"    -exec rm -rf {} + 2>/dev/null
        find "$d" -maxdepth 1 -type d -name "oci-layout-*"     -exec rm -rf {} + 2>/dev/null
        find "$d" -maxdepth 1 -type f -name "layer-*.bin"      -delete 2>/dev/null
        # The dfinit mirror check writes this marker to run once per pod; /tmp is
        # a hostPath, so it outlived restarts and clears and would skip the check
        # on a later arm whose mirror is broken.
        find "$d" -maxdepth 1 -type f -name ".riid-dfinit-mirror-ok-*" -delete 2>/dev/null
      done
      # Report bytes, not pattern hits, so a leftover nobody thought to name still
      # shows up. Two exclusion sets, because each directory has live residents:
      # config.yaml is written by the init container, and /tmp carries the daemon
      # socket plus hsperfdata from the JVM while RIID runs. No apostrophes here:
      # the whole block is one quoted argument.
      w=$(find "$RIID_WORK_DIR" -mindepth 1 -maxdepth 1 ! -name config.yaml \
            -exec du -sb {} + 2>/dev/null | awk "{s+=\$1} END{print s+0}")
      t=$(find /tmp -mindepth 1 -maxdepth 1 ! -name "riid.sock" ! -name "hsperfdata_*" \
            -exec du -sb {} + 2>/dev/null | awk "{s+=\$1} END{print s+0}")
      left=$((w + t))
      echo "    riid scratch left: $left bytes (work=$w tmp=$t)"
      [ "$left" -eq 0 ]
    '; then
    cleaned=$((cleaned + 1))
  else
    echo "clear-cache-riid: FAILED work cleanup pod=$pod" >&2
    failed=1
  fi
done

if ((failed != 0)); then
  echo "clear-cache-riid: finished with errors" >&2
  exit 1
fi
echo "clear-cache-riid: done on $cleaned pod(s)"
