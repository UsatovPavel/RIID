# Local 3-node bench stand (`bench/k8s`)

Builds the VirtualBox stand AGENT-99 measures on, from powered-off VMs to a
verified cluster. It exists because every one of these steps was previously done
by hand, and the stand was lost twice in one day to an ordinary host reboot.

```bash
make -C bench/k8s up        # full build from powered-off VMs
make -C bench/k8s recover   # after a host reboot: VMs are simply off
make -C bench/k8s verify    # assert it is actually usable
make -C bench/k8s status
```

Everything is parameterised in `stand.env`; the scripts are idempotent and safe
to re-run.

## Shape

| node | role | cluster IP | ssh |
|---|---|---|---|
| riid-n1 | control-plane + local registry, keeps its `NoSchedule` taint | 192.168.56.11 | `aibox` |
| riid-n2 | bench worker | 192.168.56.12 | `aibox2` |
| riid-n3 | bench worker | 192.168.56.13 | `aibox3` |

Two adapters per VM on purpose: NAT (nic1) for internet and the per-VM SSH
forward, host-only `vboxnet0` (nic2) for **all** cluster traffic.

## Why each script does what it does

These are not defensive habits; each one is a failure that already happened and
that looked healthy from the outside while it was happening.

**`01-host-prepare.sh`** — clones inherit one identical NAT forward, so only the
first VM to boot is reachable; each gets its own host port. NAT adapters are also
mutually isolated and every clone gets the same `10.0.2.15`, so a cluster cannot
form on NAT at all — hence the host-only adapter. Disks are grown because the
20-image dataset unpacks to ~20 GB and kubelet starts evicting with ~6.3 GB left,
so 40 GB is not enough. Sleep is inhibited because a sleeping laptop truncates an
arm into a wasted arm.

**`02-node-identity.sh`** — clones share hostname and machine-id; kubeadm needs
both unique. It ends by pinging every node from every node, because a broken mesh
is otherwise only discovered halfway through a join.

**`03-node-prep.sh`** — forces IPv4 (VirtualBox NAT has no IPv6 route while DNS
answers AAAA first, costing ~77 s per apt/curl), enables the containerd CRI, and
removes the minikube kubelet dropin left on the base image. That dropin pins
minikube's own kubelet binary and the pre-clone hostname: the symptom is a fully
healthy control plane whose Node object never registers.

**`04-cluster-init.sh`** — passes `--cri-socket` explicitly because Docker is
installed and kubeadm refuses to choose between two CRI endpoints. Pod CIDR is
`10.244.0.0/16`, not Calico's `192.168.0.0/16` default, which would swallow the
host-only network. Calico's IP autodetection is pinned to the cluster CIDR: left
alone it picks the default route, i.e. the NAT adapter, which is the *same*
address on every clone, and the workers crashloop on an address conflict.

**`05-stand-fixes.sh`** — the RIID DaemonSet mounts `/run/portod.socket` with
`hostPath type: Socket`, which kubelet asserts must exist, so RIID will not start
on a node without Porto even when the arm under test is podman. A placeholder
socket is bound with nothing listening, so a genuine Porto call fails fast instead
of appearing to work. It also removes a minikube-era `1-k8s.conflist`, which sorts
before `10-calico.conflist` (`"1-" < "10-"` lexically) and silently takes over pod
networking while Calico still reports healthy.

**`06-verify.sh`** — asserts the things that fail quietly: node InternalIPs are
not the NAT address, no node is under DiskPressure, the Calico conflist is present
and the stale one is gone, the portod placeholder is a real socket, and each
worker has the headroom an arm needs.

## Running arms

Stand construction only. The arms themselves live in `deploy/k8s/performance`;
see the `riid-local-stand` skill for the invocation flags this stand requires
(`EXPECTED_RIID_PODS`, `REGISTRY_TX_IFACE`) and for the validity checks that
separate a real measurement from a plausible-looking artefact.
