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

# Count only the repositories this arm will actually pull. The registry holds
# the gzip dataset under riid/ and the zstd one under riid-zstd/, so counting
# every repository returns 40 and the gate rejects a perfectly good stand with
# "registry incomplete".
registry_count() {
  local pod prefix="${1:-riid}"
  pod=$(kube -n registry-system get pod -l app.kubernetes.io/name=local-registry -o name 2>/dev/null | head -1)
  kube -n registry-system exec "${pod#pod/}" -- sh -c \
    "find /var/lib/registry/docker/registry/v2/repositories/${prefix} -type d -name _manifests 2>/dev/null | wc -l" 2>/dev/null | tr -dc '0-9'
}

# prefixImport lives in RIID's own config; flipping it needs a DaemonSet restart.
set_prefix_import() {
  local want="$1" cfg src
  # The init container prefers the Secret over the ConfigMap (daemonset.yaml:
  # "if [ -f /sec/config.yaml ]"), so writing the ConfigMap changes nothing when
  # the Secret exists - the flag never reaches the pod. With no runtime section
  # at all, prefixImport falls back to its built-in default, which is ON: plain
  # riid-* arms then silently take the prefix path and die on "OCI layout
  # streaming failed" / "Blob IO error". Write whichever source actually wins.
  if kube -n riid-system get secret riid-config-secret >/dev/null 2>&1; then
    src=secret
    cfg=$(kube -n riid-system get secret riid-config-secret -o jsonpath='{.data.config\.yaml}' 2>/dev/null | base64 -d) || return 1
  else
    src=configmap
    cfg=$(kube -n riid-system get cm riid-config -o jsonpath='{.data.config\.yaml}' 2>/dev/null) || return 1
  fi
  printf '%s\n' "$cfg" | grep -q '^runtime:' \
    && cfg=$(printf '%s\n' "$cfg" | sed -E "s/^  prefixImport:.*/  prefixImport: ${want}/") \
    || cfg=$(printf '%s\nruntime:\n  prefixImport: %s\n' "$cfg" "$want")
  printf '%s\n' "$cfg" | grep -q "prefixImport: ${want}" || return 1
  if [ "$src" = secret ]; then
    printf '%s' "$cfg" | kube -n riid-system create secret generic riid-config-secret \
      --from-file=config.yaml=/dev/stdin --dry-run=client -o yaml | kube apply -f - >/dev/null 2>&1 || return 1
  else
    kube -n riid-system create cm riid-config --from-literal=config.yaml="$cfg" \
      --dry-run=client -o yaml | kube apply -f - >/dev/null 2>&1 || return 1
  fi
  kube -n riid-system rollout restart daemonset/riid >/dev/null 2>&1
  kube -n riid-system rollout status daemonset/riid --timeout=8m >/dev/null 2>&1
  say "  prefixImport=${want} (via ${src})"
}

# A podman prefix arm needs the CLI path: PodmanRuntimeAdapter declines
# incremental import whenever it talks to the socket (images/load takes a whole
# archive, so growing prefixes would be O(N^2) on the wire). The node's own
# podman binary is mounted in, exactly as ctr is.
#
# Verified (AGENT-99, standalone diagnostic pod, not through this daemonset)
# what CLI mode actually needs beyond the binary+config+storage mounts already
# below - each finding is the exact error hit without it:
#
#  1. CONTAINER_HOST must be UNSET, not set to "". PodmanUnixSocketClient.
#     fromEnvironment (PodmanUnixSocketClient.java:145) treats null and ""
#     the same, but the native podman CLI child process reads its own
#     CONTAINER_HOST via Go's os.LookupEnv, which only checks presence, not
#     value - a set-but-empty var still makes it probe
#     unix:///run/podman/podman.sock ("Cannot connect to Podman ... dial unix
#     /run/podman/podman.sock: connect: no such file or directory"). Deleting
#     the env entry (not blanking it) satisfies both the Java gate and the CLI.
#  2. Three shared libraries the RIID image (Ubuntu 24.04/noble) lacks that
#     the node's podman binary is linked against (`ldd /usr/bin/podman`):
#     libsubid.so.4, libgpgme.so.11, libdevmapper.so.1.02.1. This was the
#     originally diagnosed gap ("cannot open shared object file").
#  3. conmon + libglib-2.0.so.0 (conmon's own dependency). RIID never runs a
#     container - only `podman load`/`pull`/`rmi` - but libpod's Runtime
#     bring-up resolves the OCI runtime and conmon eagerly for ANY local
#     command, container or not ("could not find a working conmon binary").
#  4. An OCI runtime binary, even though it is never invoked: same eager
#     resolution as (3). crun (podman's compiled-in first choice) is not
#     installed anywhere on this stand, node included - runc is, and the node
#     itself resolves to runc for the same reason, so mount runc.
#  5. capabilities.add SYS_ADMIN + seccompProfile/appArmorProfile Unconfined.
#     containers/storage's overlay graphdriver performs a real test mount
#     (metacopy/native-diff/userxattr probing) on every process attach, not
#     just first-time setup, on an ALREADY-initialized store. Default pod
#     confinement blocks the mount(2) syscall even with SYS_ADMIN alone
#     ("kernel does not support overlay fs ... over extfs" - misleading, the
#     real cause is confinement, not the kernel or filesystem).
#  6. The (5) fields never actually reached the pod: wrapping them as
#     "securityContext":{"$patch":"replace", <real fields>} makes `kubectl
#     patch --type strategic` report "patched (no change)" and leaves
#     securityContext null - every ControllerRevision produced by this
#     function since (5) landed still shows securityContext: null. $patch:
#     replace is only meaningful as the sole key of an object (that is how
#     the "no" branch below legitimately empties securityContext); paired
#     with sibling fields in the same object it silently no-ops the whole
#     field instead of merging them in. So the pod ran fully confined the
#     entire time, hit the exact same seccomp-blocks-mount(2) failure as (5)
#     ("... is not a shared mount" / "overlay ... not supported over extfs" -
#     the same misleading text, now with a different missing ingredient), and
#     no mount-propagation change is needed at all. Verified: the same fields
#     without the $patch wrapper apply cleanly (kubectl reports "patched", the
#     object is non-null), and with them genuinely live, an isolated pod
#     mounting the real, already-initialized /var/lib/containers with default
#     (private) mount propagation - no Bidirectional, no privileged - runs
#     `podman info` and `podman import` clean.
#  7. A second, independent bug the (6) fix exposed: the "no" branch's
#     volumeMounts $patch:delete entries matched by "name", but corev1's
#     patchMergeKey for Container.VolumeMounts is "mountPath", not "name" -
#     the API rejected the whole patch outright ("map: map[$patch:delete
#     name:lib-subid] does not contain declared merge key: mountPath"),
#     silenced by the same >/dev/null 2>&1 as everything else here. Because
#     (6) meant "yes" never did anything either, this had never mattered
#     before: reverting a no-op left nothing to revert. Once (6) is fixed,
#     "yes" actually changes the pod, so "no" must actually undo it - fixed
#     by keying volumeMounts deletion on mountPath (Volumes' own merge key,
#     "name", was already correct and untouched).
#
# None of this touches deploy/k8s/src/riid/Dockerfile.k8s: rebasing the image
# would apply to every arm's measurement, not just this one, and arms already
# measured (riid-containerd, bare-podman, ...) must stay comparable against
# the image they actually ran on. This keeps the change local to the one arm
# that needs it and reverts cleanly in the "no" branch below.
set_podman_cli_mode() {
  local want="$1"
  if [ "$want" = yes ]; then
    kube -n riid-system patch daemonset riid --type strategic -p '{"spec":{"template":{"spec":{
      "containers":[{"name":"riid",
        "env":[{"name":"CONTAINER_HOST","$patch":"delete"}],
        "securityContext":{
          "capabilities":{"add":["SYS_ADMIN"]},
          "seccompProfile":{"type":"Unconfined"},
          "appArmorProfile":{"type":"Unconfined"}},
        "volumeMounts":[
          {"name":"podman-bin","mountPath":"/usr/local/bin/podman","readOnly":true},
          {"name":"containers-etc","mountPath":"/etc/containers","readOnly":true},
          {"name":"containers-storage","mountPath":"/var/lib/containers"},
          {"name":"lib-subid","mountPath":"/usr/lib/x86_64-linux-gnu/libsubid.so.4","readOnly":true},
          {"name":"lib-gpgme","mountPath":"/usr/lib/x86_64-linux-gnu/libgpgme.so.11","readOnly":true},
          {"name":"lib-devmapper","mountPath":"/usr/lib/x86_64-linux-gnu/libdevmapper.so.1.02.1","readOnly":true},
          {"name":"lib-glib","mountPath":"/usr/lib/x86_64-linux-gnu/libglib-2.0.so.0","readOnly":true},
          {"name":"conmon-bin","mountPath":"/usr/bin/conmon","readOnly":true},
          {"name":"runc-bin","mountPath":"/usr/sbin/runc","readOnly":true}]}],
      "volumes":[
        {"name":"podman-bin","hostPath":{"path":"/usr/bin/podman","type":"File"}},
        {"name":"containers-etc","hostPath":{"path":"/etc/containers","type":"Directory"}},
        {"name":"containers-storage","hostPath":{"path":"/var/lib/containers","type":"DirectoryOrCreate"}},
        {"name":"lib-subid","hostPath":{"path":"/usr/lib/x86_64-linux-gnu/libsubid.so.4.0.0","type":"File"}},
        {"name":"lib-gpgme","hostPath":{"path":"/usr/lib/x86_64-linux-gnu/libgpgme.so.11.27.0","type":"File"}},
        {"name":"lib-devmapper","hostPath":{"path":"/usr/lib/x86_64-linux-gnu/libdevmapper.so.1.02.1","type":"File"}},
        {"name":"lib-glib","hostPath":{"path":"/usr/lib/x86_64-linux-gnu/libglib-2.0.so.0.8000.0","type":"File"}},
        {"name":"conmon-bin","hostPath":{"path":"/usr/bin/conmon","type":"File"}},
        {"name":"runc-bin","hostPath":{"path":"/usr/sbin/runc","type":"File"}}]}}}}' >/dev/null 2>&1
  else
    # Full revert: restore the socket env var and drop every mount/capability
    # added above, so socket-mode and containerd arms never inherit them.
    kube -n riid-system patch daemonset riid --type strategic -p '{"spec":{"template":{"spec":{
      "containers":[{"name":"riid",
        "env":[{"name":"CONTAINER_HOST","value":"unix:///run/podman/podman.sock"}],
        "securityContext":{"$patch":"replace"},
        "volumeMounts":[
          {"mountPath":"/usr/lib/x86_64-linux-gnu/libsubid.so.4","$patch":"delete"},
          {"mountPath":"/usr/lib/x86_64-linux-gnu/libgpgme.so.11","$patch":"delete"},
          {"mountPath":"/usr/lib/x86_64-linux-gnu/libdevmapper.so.1.02.1","$patch":"delete"},
          {"mountPath":"/usr/lib/x86_64-linux-gnu/libglib-2.0.so.0","$patch":"delete"},
          {"mountPath":"/usr/bin/conmon","$patch":"delete"},
          {"mountPath":"/usr/sbin/runc","$patch":"delete"}]}],
      "volumes":[
        {"name":"lib-subid","$patch":"delete"},
        {"name":"lib-gpgme","$patch":"delete"},
        {"name":"lib-devmapper","$patch":"delete"},
        {"name":"lib-glib","$patch":"delete"},
        {"name":"conmon-bin","$patch":"delete"},
        {"name":"runc-bin","$patch":"delete"}]}}}}' >/dev/null 2>&1
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

# A failed stand is not a failed arm. Cycling to the next arm just burns another
# 20 minutes on the same fault - nine such cycles once cost three hours, and a
# powered-off laptop cost three and a half more. Count them and stop.
stand_failed() {
  STAND_FAILS=$((STAND_FAILS + 1))
  if [ "$STAND_FAILS" -ge 2 ]; then
    say "STAND UNUSABLE: the stand failed ${STAND_FAILS} times in a row - stopping."
    say "  fix the stand (make -C bench/k8s recover; make -C bench/k8s verify), then"
    say "  restart the queue. Nothing is measured while it is in this state."
    exit 3
  fi
  return 1
}

# AGENT-112: the stand benches whatever image the DaemonSet runs, not the
# checkout, so comparing RIID versions means swapping the image and nothing
# else. Set the image with `kubectl set image` rather than re-applying the
# manifest: install-riid would strip the queue's own per-arm patches.
set_riid_image() {
  local want="$1" have
  have=$(kube -n riid-system get ds riid \
         -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)
  [ "$have" = "$want" ] && { say "  riid image already ${want##*/}"; return 0; }
  kube -n riid-system set image daemonset/riid riid="$want" >/dev/null 2>&1 || return 1
  kube -n riid-system rollout status daemonset/riid --timeout=10m >/dev/null 2>&1 || return 1
  say "  riid image ${have##*/} -> ${want##*/}"
}

run_one() {
  local arm="$1" stamp tsv before after rc n log
  stamp="$(date +%Y%m%d-%H%M)"
  log="zOptimization/clusterLogs-agent99-${stamp}"
  tsv="$PERF/output/${arm}.tsv"
  say "===== $arm (attempt $(( $(attempts_of "$arm") + 1 ))/$MAX_ATTEMPTS) ====="
  mark_attempt "$arm"

  # An arm may pin the RIID version under test; without it, whatever is
  # deployed stays. The image is recorded in the arm's own log either way.
  if [ -n "${RIID_IMAGE:-}" ]; then
    set_riid_image "$RIID_IMAGE" || { say "$arm: could not set image $RIID_IMAGE"; return 1; }
  fi

  # The stand is usually "broken" only because the laptop rebooted.
  # recover's exit status used to be discarded: it reported "aibox not reachable"
  # and cold-cache then ground away against a dead API server for two hours
  # before anyone noticed. A stand that did not come back is a stand failure, so
  # fail here instead of paying for that discovery downstream. The timeout is the
  # backstop for the same lesson - cold-cache is a ~20 min job and must never be
  # able to consume a whole window on its own.
  bash "${STAND_DIR}/recover.sh" > "$STATE/$arm.recover.log" 2>&1
  # recover ends by exec'ing 06-verify.sh, so its exit status is that verify's
  # fail count - and most of those failures are exactly what cold-cache exists
  # to repair (a disk filled by the previous arm, stale caches). Aborting on any
  # of them stops the queue for a condition the next step would have fixed.
  # What must abort is the stand not being THERE: that is the case that cost two
  # hours grinding cold-cache against a dead API server.
  ready=$(kube get nodes --no-headers 2>/dev/null | grep -cw Ready)
  if [ "${ready:-0}" -lt "$(stand_count)" ]; then
    say "$arm: only ${ready:-0}/$(stand_count) nodes Ready after recover - the stand did not come back"
    tail -2 "$STATE/$arm.recover.log"
    stand_failed; return $?
  fi
  if ! timeout 45m bash "${STAND_DIR}/cold-cache.sh" > "$STATE/$arm.coldcache.log" 2>&1 \
     || ! tail -3 "$STATE/$arm.coldcache.log" | grep -q 'stand verified'; then
    say "$arm: cold-cache did not reach 'stand verified' - not starting the arm"
    grep -E '  FAIL' "$STATE/$arm.coldcache.log" 2>/dev/null | tail -3
    stand_failed; return $?
  fi
  STAND_FAILS=0
  local dsprefix=riid
  case "$arm" in *-zstd) dsprefix=riid-zstd;; esac
  n="$(registry_count "$dsprefix")"; say "  registry holds ${n:-0}/20 ${dsprefix}/ repositories"
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

  # ENGINE must be the bare engine name. install-dragonfly.sh branches on an
  # exact string match ("containerd"), so a suffixed arm like
  # dfinit-containerd-zstd used to pass ENGINE=containerd-zstd, miss the branch,
  # and install the client with NO containerd dfinit override at all - the exact
  # condition the script warns crashlooped the whole client DaemonSet. Strip the
  # dataset and flag suffixes before handing the name over.
  case "$arm" in dfinit-*)
    eng="${arm#dfinit-}"; eng="${eng%-zstd}"; eng="${eng%-prefix}"
    make -C deploy/k8s/bootstrap dfinit-enable \
      ENGINE="$eng" $CF > "$STATE/$arm.dfinit.log" 2>&1;; esac

  # The prefix arm is the same engine arm with runtime.prefixImport flipped on;
  # the flag lives in RIID's config, not in a make target.
  local base="$arm" prefix=no dsargs=""
  case "$arm" in *-prefix) base="${arm%-prefix}"; prefix=yes;; esac
  # A zstd arm is the same arm against the zstd copy of the same 20 images, so
  # it needs no new make target - just the other dataset file and its own output
  # file, since the runner names output by backend label and would otherwise
  # overwrite the gzip result. The zstd dataset is 220 distinct blobs at
  # 10.17 GiB against gzip's 11.07 GiB, so its ratios divide by 10.17.
  case "$arm" in
    # Absolute paths: make -C runs the scenario from another directory, and a
    # relative DATASET_FILE resolves against that one ("Dataset file not found").
    *-zstd) base="${base%-zstd}"
            dsargs="DATASET_FILE=$(pwd)/$PERF/input/dataset_a_zstd.tsv OUTPUT_TSV=$(pwd)/$PERF/output/${arm}.tsv";;
  esac
  if [ "$prefix" = yes ]; then
    set_prefix_import true || { say "$arm: could not enable prefixImport"; return 1; }
    case "$base" in riid-podman) set_podman_cli_mode yes;; esac
  fi
  # RIID gained the podman socket in 0.4.11 ("Engine: podman socket switch").
  # Older builds have no socketClient and no CONTAINER_HOST at all - they always
  # shell out to the podman binary - so benching one on podman needs CLI mode
  # even without prefix import. AGENT-112 compares 0.4.5 against 0.4.11, and this
  # transport difference IS the change between them, not a stand detail.
  if [ "${RIID_PODMAN_CLI:-0}" = "1" ] && [ "$prefix" = no ]; then
    case "$base" in riid-podman) set_podman_cli_mode yes;; esac
  elif [ "$prefix" = no ]; then
    # CLI mode is only ever undone by the arm that turned it on, so an arm that
    # died before its cleanup leaves the podman shim mounted in the RIID
    # container - and engine_preflight then refuses EVERY podman arm with
    # "podman must not be installed in the RIID container". Three bare-podman
    # attempts and a bare-podman-zstd attempt were burned that way. Assert the
    # clean state on entry instead of trusting the previous arm's exit path.
    if kube -n riid-system exec "$(kube -n riid-system get pods \
         -l app.kubernetes.io/name=riid -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)" \
         -c riid -- sh -c 'command -v podman' >/dev/null 2>&1; then
      say "  podman shim left over from an earlier arm - reverting CLI mode"
      set_podman_cli_mode no
    fi
  fi

  tsv="$PERF/output/${base}.tsv"
  case "$arm" in *-zstd) tsv="$PERF/output/${arm}.tsv";; esac
  before=$(stat -c %Y "$tsv" 2>/dev/null || echo 0)
  # Per-blob capture: ctr prints one progress line per manifest, config and layer
  # on stdout, which the scenario normally discards. Point the engine at a file
  # beside the arm's own logs so the detail survives with the run.
  if [ "${CONTAINERD_DEBUG:-0}" = "1" ]; then
    mkdir -p "$log/$arm"
    export CONTAINERD_DEBUG_LOG="$(pwd)/$log/$arm/ctr-blobs.log"
    : > "$CONTAINERD_DEBUG_LOG"
    say "  per-blob ctr log: $log/$arm/ctr-blobs.log"
  fi
  make -C "$PERF" "$base" $CF EXPECTED_RIID_PODS=2 REGISTRY_TX_IFACE="$STAND_CLUSTER_NIC" $dsargs \
    > "$STATE/$arm.run.log" 2>&1
  rc=$?
  # Export before restoring anything: both restore steps roll the RIID
  # DaemonSet, and a rolled pod takes its log with it. Every prefix arm so far
  # exported exactly 2134 bytes of fresh-pod startup chatter, which also made
  # the p2p/registry counters below read 0/0 - unprovable, not just untidy.
  # The precondition record must travel with the run it belongs to. These logs
  # are written per ARM NAME, so a second run of the same arm overwrites the
  # first one's evidence - and a question about that run's cold-cache then has
  # no answer at all, which is exactly what happened comparing 0.4.12 to 0.4.13.
  mkdir -p "$log/$arm"
  for f in recover coldcache; do
    [ -f "$STATE/$arm.$f.log" ] && cp "$STATE/$arm.$f.log" "$log/$arm/$f.log"
  done
  export_logs "$arm" "$log"
  if [ "$prefix" = yes ]; then
    set_prefix_import false
    case "$base" in riid-podman) set_podman_cli_mode no;; esac
  elif [ "${RIID_PODMAN_CLI:-0}" = "1" ]; then
    case "$base" in riid-podman) set_podman_cli_mode no;; esac
  fi
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

  # The runner names its output by the BACKEND label, so a prefix arm and its
  # plain counterpart both write output/riid-containerd.tsv and the second one
  # silently clobbers the first. The timestamped copy below is the record of
  # this run; also keep an arm-named copy so the generic file is never the only
  # place an arm's result lives.
  cp "$tsv" "$PERF/output/${arm}.agent99-${stamp}.tsv"
  # A zstd arm already writes to its own arm-named file via OUTPUT_TSV, so the
  # arm-named copy would be the file onto itself.
  [ "$tsv" = "$PERF/output/${arm}.tsv" ] || cp "$tsv" "$PERF/output/${arm}.tsv"
  say "  images=$(awk -F, 'NR>1 && $4=="AGGREGATE"' "$tsv" | wc -l)/20 failures=$(awk -F, 'NR>1 && $9!=0 && $9!=""' "$tsv" | wc -l)"
  awk -F, 'NR>1 && $4=="AGGREGATE"{s+=$8} END{if(s>0) printf "  sum AGGREGATE: %.1f s\n", s/1000}' "$tsv"
  grep registry_tx_bytes_delta "$tsv" | awk -F'\t' '{printf "  egress: %.2f GiB\n", $2/1073741824}'
  case "$arm" in riid-*|dfinit-*)
    say "  p2p=$(grep -ho 'Source fetched: p2p' "$log/$arm"/riid/*.log 2>/dev/null | wc -l) registry=$(grep -ho 'Source fetched: registry' "$log/$arm"/riid/*.log 2>/dev/null | wc -l)"
    # One line per event, but the substring occurs twice inside it (once as the
    # prefix of NeedBackToSourceResponse, once in its description), so grep -o
    # doubles the count. Match the line instead of the substring and the /2 that
    # used to compensate is no longer needed. These events land in the SEED logs:
    # a zero here means the seed tier never fetched anything, which is what
    # separated dfinit-containerd (0, egress x1.83) from riid-containerd (222,
    # egress x1.25) - the counter is a seed-participation signal, not noise.
    say "  NeedBackToSource(tx)=$(grep -hc 'need back to source response' "$log/$arm"/seed/*.log "$log/$arm"/dfdaemon/*.log 2>/dev/null | paste -sd+ | bc)"
    # A throw from the puller's close() discards an already-finished P2P
    # download, and the layer is then paid for a second time from the registry.
    # The rate is a race, not a property of the arm, so it has to be reported
    # next to the timing or two riid arms are comparing noise.
    say "  p2p-discarded-after-download=$(grep -ho 'failed to close dragonfly puller' "$log/$arm"/riid/*.log 2>/dev/null | wc -l)"
    ;;
  esac
  touch "$STATE/$arm.done"
  say "  riid image: $(kube -n riid-system get ds riid -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
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
