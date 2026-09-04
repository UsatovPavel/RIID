#!/usr/bin/env bash
# Self-sustaining arm queue. Runs whatever is still owed, one arm at a time,
# and keeps going on its own - the reason ~16 hours of stand time was lost was
# not slow arms but one-shot batches that ended with nobody left to start the
# next one.
#
#   ./arm-queue.sh              # work the queue until it is empty
#   ARMS="riid-podman" ./arm-queue.sh
#
# State lives in queue-state/ so a restart resumes instead of redoing work.
set -uo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

REPO="$(cd "${STAND_DIR}/../.." && pwd)"
PERF="deploy/k8s/performance"
CF="CONFIG_FILE=${KUBECONFIG_OUT}"
STATE="${STAND_DIR}/queue-state"
mkdir -p "$STATE"
cd "$REPO" || die "repo not found"

# gzip phase. riid-podman/dfinit-podman/bare-containerd are already measured.
# NOTE: a podman prefix arm is impossible here - PodmanRuntimeAdapter returns
# socketClient.isEmpty() && prefixImport, and the stand runs podman over its
# socket, so prefix is declined whatever the config says. containerd has no
# such restriction, so the prefix arm is measured there.
ARMS="${ARMS:-bare-podman riid-containerd dfinit-containerd riid-podman-prefix riid-containerd-prefix}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-3}"
STAND_FAILS=0

attempts_of() { cat "$STATE/$1.attempts" 2>/dev/null || echo 0; }
mark_attempt() { echo $(( $(attempts_of "$1") + 1 )) > "$STATE/$1.attempts"; }
is_done()      { [ -f "$STATE/$1.done" ]; }

registry_count() {
  local pod
  pod=$(kube -n registry-system get pod -l app.kubernetes.io/name=local-registry -o name 2>/dev/null | head -1)
  kube -n registry-system exec "${pod#pod/}" -- sh -c \
    'find /var/lib/registry/docker/registry/v2/repositories -type d -name _manifests 2>/dev/null | wc -l' 2>/dev/null | tr -dc '0-9'
}

# prefixImport lives in RIID's own config; flipping it needs a DaemonSet restart.
set_prefix_import() {
  local want="$1" cfg
  cfg=$(kube -n riid-system get cm riid-config -o jsonpath='{.data.config\.yaml}' 2>/dev/null) || return 1
  printf '%s\n' "$cfg" | grep -q '^runtime:' \
    && cfg=$(printf '%s\n' "$cfg" | sed -E "s/^  prefixImport:.*/  prefixImport: ${want}/") \
    || cfg=$(printf '%s\nruntime:\n  prefixImport: %s\n' "$cfg" "$want")
  printf '%s\n' "$cfg" | grep -q "prefixImport: ${want}" || return 1
  kube -n riid-system create cm riid-config --from-literal=config.yaml="$cfg" \
    --dry-run=client -o yaml | kube apply -f - >/dev/null 2>&1 || return 1
  kube -n riid-system rollout restart daemonset/riid >/dev/null 2>&1
  kube -n riid-system rollout status daemonset/riid --timeout=8m >/dev/null 2>&1
  say "  prefixImport=${want}"
}

# A podman prefix arm needs the CLI path: PodmanRuntimeAdapter declines
# incremental import whenever it talks to the socket (images/load takes a whole
# archive, so growing prefixes would be O(N^2) on the wire). A blank
# CONTAINER_HOST selects the CLI fallback - and the RIID image ships no podman,
# so the node's own binary is mounted in, exactly as ctr is.
set_podman_cli_mode() {
  local want="$1"
  if [ "$want" = yes ]; then
    # The binary alone is not enough: the RIID image ships no containers stack,
    # so podman CLI finds no /etc/containers (policy.json, storage.conf) and
    # every import returns HTTP 500. Hand it the node's config and the node's
    # storage - the engine under test is the node's podman either way, the CLI
    # is only a different transport to the same graphroot.
    kube -n riid-system patch daemonset riid --type strategic -p '{"spec":{"template":{"spec":{
      "containers":[{"name":"riid","env":[{"name":"CONTAINER_HOST","value":""}],
        "volumeMounts":[
          {"name":"podman-bin","mountPath":"/usr/local/bin/podman","readOnly":true},
          {"name":"containers-etc","mountPath":"/etc/containers","readOnly":true},
          {"name":"containers-storage","mountPath":"/var/lib/containers"}]}],
      "volumes":[
        {"name":"podman-bin","hostPath":{"path":"/usr/bin/podman","type":"File"}},
        {"name":"containers-etc","hostPath":{"path":"/etc/containers","type":"Directory"}},
        {"name":"containers-storage","hostPath":{"path":"/var/lib/containers","type":"DirectoryOrCreate"}}]}}}}' >/dev/null 2>&1
  else
    kube -n riid-system patch daemonset riid --type strategic -p '{"spec":{"template":{"spec":{
      "containers":[{"name":"riid","env":[{"name":"CONTAINER_HOST","value":"unix:///run/podman/podman.sock"}]}]}}}}' >/dev/null 2>&1
  fi
  kube -n riid-system rollout status daemonset/riid --timeout=8m >/dev/null 2>&1
  say "  podman CLI mode=${want}"
}

export_logs() {
  local arm="$1" out="$2/$1"
  mkdir -p "$out"/{dfdaemon,scheduler,seed,riid}
  for p in $(kube -n dragonfly-system get pods -l app=dragonfly,component=client -o name 2>/dev/null); do
    kube -n dragonfly-system logs "${p#pod/}" -c client --tail=1000000 --timestamps > "$out/dfdaemon/${p#pod/}.log" 2>/dev/null; done
  for p in $(kube -n dragonfly-system get pods -l app=dragonfly,component=scheduler -o name 2>/dev/null); do
    kube -n dragonfly-system logs "${p#pod/}" -c scheduler --tail=1000000 --timestamps > "$out/scheduler/${p#pod/}.log" 2>/dev/null; done
  for p in $(kube -n dragonfly-system get pods -l app=dragonfly,component=seed-client -o name 2>/dev/null); do
    kube -n dragonfly-system logs "${p#pod/}" -c seed-client --tail=1000000 --timestamps > "$out/seed/${p#pod/}.log" 2>/dev/null; done
  for p in $(kube -n riid-system get pods -l app.kubernetes.io/name=riid -o name 2>/dev/null); do
    kube -n riid-system logs "${p#pod/}" -c riid --tail=1000000 --timestamps > "$out/riid/${p#pod/}.log" 2>/dev/null
    # A pod that died mid-arm is replaced, and the export then captures the
    # fresh pod's empty log while the evidence of why it died is gone. Keep the
    # previous container's output and the termination reason as well.
    kube -n riid-system logs "${p#pod/}" -c riid --previous --tail=1000000 --timestamps \
      > "$out/riid/${p#pod/}.previous.log" 2>/dev/null
    [ -s "$out/riid/${p#pod/}.previous.log" ] || rm -f "$out/riid/${p#pod/}.previous.log"
    kube -n riid-system describe pod "${p#pod/}" > "$out/riid/${p#pod/}.describe.txt" 2>/dev/null
  done
  # Events explain an eviction or OOM kill that no container log will show.
  kube -n riid-system get events --sort-by=.lastTimestamp > "$out/riid/events.txt" 2>/dev/null
  kube get nodes -o wide > "$out/riid/nodes.txt" 2>/dev/null
  { echo "# $arm"; echo "captured: $(date -Is)"; echo;
    find "$out" -type f -name '*.log' -printf '%p %s bytes\n' | sort; } > "$out/README.md"
  ( cd "$out" && find . -type f -name '*.log' -exec sha256sum {} + > SHA256SUMS 2>/dev/null )
}

run_one() {
  local arm="$1" stamp tsv before after rc n log
  stamp="$(date +%Y%m%d-%H%M)"
  log="zOptimization/clusterLogs-agent99-${stamp}"
  tsv="$PERF/output/${arm}.tsv"
  say "===== $arm (attempt $(( $(attempts_of "$arm") + 1 ))/$MAX_ATTEMPTS) ====="
  mark_attempt "$arm"

  # The stand is usually "broken" only because the laptop rebooted.
  bash "${STAND_DIR}/recover.sh" > "$STATE/$arm.recover.log" 2>&1
  if ! bash "${STAND_DIR}/cold-cache.sh" > "$STATE/$arm.coldcache.log" 2>&1 \
     || ! tail -3 "$STATE/$arm.coldcache.log" | grep -q 'stand verified'; then
    say "$arm: cold-cache did not reach 'stand verified' - not starting the arm"
    grep -E '  FAIL' "$STATE/$arm.coldcache.log" 2>/dev/null | tail -3
    # A failed cold-cache means the STAND is unusable, not that this arm is bad.
    # Cycling to the next arm just burns another 20 minutes on the same fault -
    # nine such cycles once cost three hours, and a powered-off laptop cost
    # three and a half more. Count them and stop rather than churn.
    STAND_FAILS=$((STAND_FAILS + 1))
    if [ "$STAND_FAILS" -ge 2 ]; then
      say "STAND UNUSABLE: cold-cache failed ${STAND_FAILS} times in a row - stopping."
      say "  fix the stand (make -C bench/k8s recover; make -C bench/k8s verify), then"
      say "  restart the queue. Nothing is measured while it is in this state."
      exit 3
    fi
    return 1
  fi
  STAND_FAILS=0
  n="$(registry_count)"; say "  registry holds ${n:-0}/20 repositories"
  [ "${n:-0}" = "20" ] || { say "$arm: registry incomplete - skipping"; return 1; }

  # A baseline arm must see no mirror, and dfinit re-writes the node's
  # registries.conf from its init container every time a dragonfly-client pod
  # starts - which cold-cache does before every arm. So the pristine copy has to
  # go back HERE, after cold-cache and immediately before the pull loop, not
  # after whichever arm happened to be dfinit. Otherwise engine_no_mirror_check
  # correctly aborts all 20 images in ~3s and the arm burns every retry.
  case "$arm" in bare-*)
    i=0
    for alias in $STAND_SSH; do
      i=$((i+1)); [ "$i" = 1 ] && continue
      node_sudo "$alias" "sh -c 'test -f /etc/containers/registries.conf.riid-baseline && cp /etc/containers/registries.conf.riid-baseline /etc/containers/registries.conf'"
    done
    say "  restored the pristine registries.conf for a baseline arm"
    ;;
  esac

  case "$arm" in dfinit-*) make -C deploy/k8s/bootstrap dfinit-enable \
      ENGINE="${arm#dfinit-}" $CF > "$STATE/$arm.dfinit.log" 2>&1;; esac

  # The prefix arm is the same engine arm with runtime.prefixImport flipped on;
  # the flag lives in RIID's config, not in a make target.
  local base="$arm" prefix=no
  case "$arm" in *-prefix) base="${arm%-prefix}"; prefix=yes;; esac
  if [ "$prefix" = yes ]; then
    set_prefix_import true || { say "$arm: could not enable prefixImport"; return 1; }
    case "$base" in riid-podman) set_podman_cli_mode yes;; esac
  fi

  tsv="$PERF/output/${base}.tsv"
  before=$(stat -c %Y "$tsv" 2>/dev/null || echo 0)
  make -C "$PERF" "$base" $CF EXPECTED_RIID_PODS=2 REGISTRY_TX_IFACE="$STAND_CLUSTER_NIC" \
    > "$STATE/$arm.run.log" 2>&1
  rc=$?
  if [ "$prefix" = yes ]; then
    set_prefix_import false
    case "$base" in riid-podman) set_podman_cli_mode no;; esac
  fi
  export_logs "$arm" "$log"
  case "$arm" in dfinit-*)
    make -C deploy/k8s/bootstrap dfinit-disable $CF >/dev/null 2>&1
    # dfinit-disable stops the mirror going forward but leaves the node's
    # registries.conf rewritten. A later bare-* arm then refuses to run at all
    # ("baseline arm is contaminated"), which is correct - it would otherwise
    # measure a baseline that quietly pulls through the mirror. Put the
    # pristine copy back so the next baseline is honest.
    i=0
    for alias in $STAND_SSH; do
      i=$((i+1)); [ "$i" = 1 ] && continue
      node_sudo "$alias" "sh -c 'test -f /etc/containers/registries.conf.riid-baseline && cp /etc/containers/registries.conf.riid-baseline /etc/containers/registries.conf'"
    done
    say "  restored the pristine registries.conf on the workers"
    ;;
  esac

  after=$(stat -c %Y "$tsv" 2>/dev/null || echo 0)
  if [ "$rc" -ne 0 ] || [ "$after" = "$before" ]; then
    say "$arm: FAILED (rc=$rc, tsv rewritten=$([ "$after" != "$before" ] && echo yes || echo no))"
    tail -3 "$STATE/$arm.run.log"
    return 1
  fi

  cp "$tsv" "$PERF/output/${arm}.agent99-${stamp}.tsv"
  say "  images=$(awk -F, 'NR>1 && $4=="AGGREGATE"' "$tsv" | wc -l)/20 failures=$(awk -F, 'NR>1 && $9!=0 && $9!=""' "$tsv" | wc -l)"
  awk -F, 'NR>1 && $4=="AGGREGATE"{s+=$8} END{if(s>0) printf "  sum AGGREGATE: %.1f s\n", s/1000}' "$tsv"
  grep registry_tx_bytes_delta "$tsv" | awk -F'\t' '{printf "  egress: %.2f GiB\n", $2/1073741824}'
  case "$arm" in riid-*|dfinit-*)
    say "  p2p=$(grep -ho 'Source fetched: p2p' "$log/$arm"/riid/*.log 2>/dev/null | wc -l) registry=$(grep -ho 'Source fetched: registry' "$log/$arm"/riid/*.log 2>/dev/null | wc -l)"
    # Two log lines per transaction, so halve it - counting raw lines doubled it once.
    say "  NeedBackToSource(tx)=$(( $(grep -ho 'NeedBackToSource' "$log/$arm"/seed/*.log "$log/$arm"/dfdaemon/*.log 2>/dev/null | wc -l) / 2 ))"
    ;;
  esac
  touch "$STATE/$arm.done"
  say "$arm: DONE -> ${arm}.agent99-${stamp}.tsv"
}

say "queue: $ARMS (max $MAX_ATTEMPTS attempts each)"
progress=1
while [ "$progress" = 1 ]; do
  progress=0
  for arm in $ARMS; do
    is_done "$arm" && continue
    [ "$(attempts_of "$arm")" -ge "$MAX_ATTEMPTS" ] && continue
    run_one "$arm" && progress=1 || progress=1   # any attempt counts as progress
  done
done
say "== QUEUE EXHAUSTED =="
for arm in $ARMS; do
  printf '  %-20s %s (attempts %s)\n' "$arm" \
    "$(is_done "$arm" && echo DONE || echo UNRESOLVED)" "$(attempts_of "$arm")"
done
