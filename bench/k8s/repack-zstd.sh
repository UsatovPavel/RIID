#!/usr/bin/env bash
# Repacks the bench dataset from gzip into zstd INSIDE the cluster.
#
# The AGENT-97 method (bench/zstd_bench.py) is a pull-then-push with
# --compression-format zstd --force-compression. The upstream copy lives in
# Selectel's CR, but that WAN link is unreachable from this stand, and the
# 20-image dataset is already mirrored into the in-cluster registry - so
# repack from there and the WAN is not needed at all. Roughly 90-100 minutes
# of Selectel download time is avoided this way, and the source bytes are
# provably the ones the gzip arms measured.
#
# Non-destructive by default: zstd lands under a separate DEST_PREFIX so the
# gzip dataset survives. That matters because the gzip dataset cannot be
# re-fetched while the WAN is down - do not delete it until the last gzip arm
# is measured.
#
#   ./repack-zstd.sh            # repack all 20 repos into riid-zstd/*
#   REPOS="riid/nginx" ./repack-zstd.sh
set -euo pipefail
STAND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=stand.env
set -a; . "${STAND_DIR}/stand.env"; set +a
# shellcheck source=lib.sh
. "${STAND_DIR}/lib.sh"

# The workers keep dfinit's mirror in registries.conf (prefix 10.96.5.146:5000
# -> 127.0.0.1:4001), so a pull addressed to the service IP silently travels
# through Dragonfly's HTTP proxy. That proxy dies on the 5.4 GB datasense blob:
#   Error: writing blob: ... unexpected EOF (after reconnecting, server did not
#   process a Range: header, status 200)
# 19 of 20 images repacked through it and only the largest failed; the same pull
# addressed to the registry POD ip - which matches no mirror rule - succeeded
# first try. Repacking must not go through P2P anyway: it is dataset
# preparation, not a measurement. So resolve the pod ip and pull from that.
REGISTRY="${REGISTRY:-10.96.5.146:5000}"
PULL_FROM="${PULL_FROM:-$(kube -n registry-system get pods \
  -o jsonpath='{.items[0].status.podIP}' 2>/dev/null):5000}"
[ "$PULL_FROM" = ":5000" ] && PULL_FROM="$REGISTRY"
DEST_PREFIX="${DEST_PREFIX:-riid-zstd}"
SRC_PREFIX="${SRC_PREFIX:-riid}"
ZSTD_LEVEL="${ZSTD_LEVEL:-3}"
# The worker doing the work. It needs podman and a route to the registry
# service IP; any worker qualifies, and using one keeps the other free.
WORKER="${WORKER:-aibox2}"

catalog() {
  node_run "$WORKER" "curl -sS -m 30 http://${REGISTRY}/v2/_catalog?n=200" \
    | python3 -c 'import sys,json; print("\n".join(json.load(sys.stdin).get("repositories",[])))'
}

tags_of() {
  node_run "$WORKER" "curl -sS -m 30 http://${REGISTRY}/v2/$1/tags/list" \
    | python3 -c 'import sys,json; t=json.load(sys.stdin).get("tags") or []; print(t[0] if t else "")'
}

REPOS="${REPOS:-$(catalog | grep "^${SRC_PREFIX}/" || true)}"
[ -n "$REPOS" ] || { echo "no repositories under ${SRC_PREFIX}/ in ${REGISTRY}"; exit 1; }

say "repacking $(printf '%s\n' $REPOS | wc -l) repositories to ${DEST_PREFIX}/ (zstd level ${ZSTD_LEVEL}) on ${WORKER}"
ok=0; fail=0
for repo in $REPOS; do
  tag="$(tags_of "$repo")"
  [ -n "$tag" ] || { say "  SKIP $repo (no tags)"; fail=$((fail+1)); continue; }
  src="${PULL_FROM}/${repo}:${tag}"
  dst="${REGISTRY}/${DEST_PREFIX}/${repo#${SRC_PREFIX}/}:${tag}"
  # --force-compression is what actually rewrites the blobs; without it podman
  # reuses the source's gzip layers and the "zstd" copy is gzip with a new name.
  # Two calls on purpose: node_sudo runs "sudo -S <cmd>", so a shell && inside
  # one call would run the second command WITHOUT sudo and it fails to reach
  # podman's root storage.
  if node_sudo "$WORKER" "podman pull --tls-verify=false '$src'" >/dev/null 2>&1 \
     && node_sudo "$WORKER" "podman push --tls-verify=false --format oci \
          --compression-format zstd --compression-level ${ZSTD_LEVEL} \
          --force-compression '$src' '$dst'" >/dev/null 2>&1; then
    say "  ok   ${repo}:${tag}"
    ok=$((ok+1))
  else
    say "  FAIL ${repo}:${tag}"
    fail=$((fail+1))
  fi
  # The node has ~50 GB free and the dataset unpacks to ~20 GB per copy, so
  # drop each image once it is pushed rather than at the end.
  node_sudo "$WORKER" "podman rmi -f '$src' '$dst' >/dev/null 2>&1" || true
done
say "repacked ${ok} ok, ${fail} failed -> ${REGISTRY}/${DEST_PREFIX}/"
[ "$fail" = 0 ]
