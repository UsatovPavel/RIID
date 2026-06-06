#!/usr/bin/env bash
# Builds grafana-dashboards-json + grafana-home-dashboard ConfigMaps from the repo source of truth
# (config/metrics/grafana/dashboards/*.json and home/riid-home.json). Not part of Helm .Files.
set -euo pipefail
NS="${1:-riid-system}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../../../" && pwd)"
DASH_DIR="${REPO_ROOT}/config/metrics/grafana/dashboards"
HOME_JSON="${REPO_ROOT}/config/metrics/grafana/home/riid-home.json"

[[ -d "${DASH_DIR}" ]] || { echo "apply-grafana-configmaps-from-repo: missing ${DASH_DIR}" >&2; exit 1; }
[[ -f "${HOME_JSON}" ]] || { echo "apply-grafana-configmaps-from-repo: missing ${HOME_JSON}" >&2; exit 1; }

from_files=()
shopt -s nullglob
for f in "${DASH_DIR}"/*.json; do
  from_files+=(--from-file="$(basename "${f}")=${f}")
done
shopt -u nullglob

if ((${#from_files[@]} == 0)); then
  echo "apply-grafana-configmaps-from-repo: no *.json in ${DASH_DIR}" >&2
  exit 1
fi

kubectl create configmap grafana-dashboards-json -n "${NS}" "${from_files[@]}" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl create configmap grafana-home-dashboard -n "${NS}" \
  --from-file=riid-home.json="${HOME_JSON}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Applied Grafana ConfigMaps from ${REPO_ROOT}/config/metrics/grafana/ (namespace ${NS})"
