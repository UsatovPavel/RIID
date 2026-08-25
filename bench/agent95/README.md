# AGENT-95: engine-side import switches

Measures the four engine-configuration levers of AGENT-95 without a network in the
timed section: the images are fetched into local OCI layouts first, and only the
handoff to the engine is measured. Results and verdicts:
`zOptimization/Plan/PlanEngineSideOptimizations.md` §6, ADR-12.

The image set is deliberately *not* `bench/config/images.py`: that one was built
without a shared base, and `use_hard_links` needs the opposite — python 3.11 /
node 20 / ruby carry differently-digested debian bases with near-identical file
content, which is the case chain-id reuse does not cover.

## Fetch the layouts (once)

```bash
for r in library/python:3.11-slim-bookworm library/python:3.12-slim-bookworm \
         library/python:3.13-slim-bookworm library/node:18-slim library/node:20-slim \
         library/node:22-slim library/ruby:3.2-slim library/ruby:3.3-slim; do
  python3 fetch_layout.py "$r" "layouts/$(echo "$r" | tr '/:' '__')"
done
```

## 1.1 podman `use_hard_links` (host)

```bash
ROUNDS=3 ./hardlink_bench.sh   # A/B on RIID's path (podman pull oci:)
./hardlink_reach.sh            # is the knob reachable there at all?
./hardlink_registry.sh         # control: same knob on a registry pull
```

All three run against an isolated store via `CONTAINERS_STORAGE_CONF` and remove it
afterwards; the host's own podman store and `~/.config/containers` are never touched.

## 1.2-1.4 containerd (VM, as root)

`containerd_bench.sh` wipes `/var/lib/containerd` and restarts containerd between
arms, so every arm starts on a clean node.

```bash
python3 prefix_layouts.py <layout> prefixes/<name> <session-id>  # per image, for 1.4
sudo ./containerd_bench.sh 12   # base | --local | --local --discard-unpacked-layers
sudo ./containerd_bench.sh 13   # overlayfs | --local overlayfs | --local native
sudo ./containerd_bench.sh 14   # prefix sequence with and without --no-unpack
sudo ./containerd_verify.sh     # run/export after discard; prefix import under discard
```

`prefix_layouts.py` reproduces what `PrefixImportLayouts` hands containerd —
`LayerScope.ADDED_ONLY`, config cut to the prefix length, history dropped — so the
CLI arms match the adapter rather than approximating it.
