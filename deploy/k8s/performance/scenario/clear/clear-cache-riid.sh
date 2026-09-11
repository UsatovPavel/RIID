#!/usr/bin/env bash
# RIID's own cache only: the OCI scratch it builds before handing an image to an
# engine. Not the engine store (clear-cache-engines.sh) and not Dragonfly
# (clear-cache-dragonfly.sh) - RIID writes into all three and each is cleared by
# its own script so an arm can wipe exactly what it used.
#
# What lives here: riid-cache-tmp-* and riid-prefix-* directories plus orphaned
# layer-*.bin, under app.tempDirectory (RIID_WORK_DIR) and /tmp inside the pod.
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

if [[ -n "${KUBECONFIG:-}" && ! -f "$KUBECONFIG" ]]; then
  echo "clear-cache-riid: kubeconfig not found: $KUBECONFIG" >&2
  exit 1
fi

mapfile -t pods < <(kubectl -n "$NS" get pods -l "$LABEL" \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')

if ((${#pods[@]} == 0)); then
  echo "clear-cache-riid: no pods with label $LABEL in $NS" >&2
  exit 1
fi

failed=0
cleaned=0

for pod in "${pods[@]}"; do
  [[ -z "$pod" ]] && continue
  phase=$(kubectl -n "$NS" get pod "$pod" -o jsonpath='{.status.phase}')
  if [[ "$phase" != Running ]]; then
    echo ">>> skip $pod (phase=$phase)" >&2
    continue
  fi
  if kubectl -n "$NS" exec -c "$CONTAINER" "$pod" -- env RIID_WORK_DIR="$WORK_DIR" sh -ec '
      left=0
      for d in "$RIID_WORK_DIR" /tmp; do
        [ -d "$d" ] || continue
        find "$d" -maxdepth 1 -type d -name "riid-cache-tmp-*" -exec rm -rf {} + 2>/dev/null
        find "$d" -maxdepth 1 -type d -name "riid-prefix-*"    -exec rm -rf {} + 2>/dev/null
        find "$d" -maxdepth 1 -type f -name "layer-*.bin"      -delete 2>/dev/null
        n=$(find "$d" -maxdepth 1 \( -name "riid-cache-tmp-*" -o -name "riid-prefix-*" -o -name "layer-*.bin" \) 2>/dev/null | wc -l)
        left=$((left + n))
      done
      echo "    riid scratch left: $left"
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
