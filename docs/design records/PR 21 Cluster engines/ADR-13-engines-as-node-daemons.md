# ADR-13: every container engine is a daemon on the node, the pod holds only a client

## Context

The AGENT-99 matrix has three engines. containerd and Porto were already node
daemons (`containerd.sock`, `portod.socket`), but podman lived inside the RIID
pod: a binary in the image, `graphroot` with no hostPath under it, and
`mount_program = fuse-overlayfs`. Every import therefore unpacked through
userspace FUSE into the container's own writable layer — overlay on overlay.

That broke both goals at once. First, `engine.import` is 76% of a cold request
(ADR-11), and we were measuring it on a rig that manufactured its own overhead.
Second, the `RIID => Podman` arm was not comparable with `RIID => Containerd` or
`RIID => Porto`: podman imported locally while the other two went through a
daemon socket.

## Decision

Run podman on the node through its packaged `podman.socket` (DaemonSet
`src/engines/podman-node.yaml`) and let the pod reach it over `CONTAINER_HOST`.
`PodmanRuntimeAdapter` sends the image archive directly to
`POST /v4.0.0/libpod/images/load` over that Unix socket. A blank
`CONTAINER_HOST` retains the CLI path for local development, but the Kubernetes
image contains no Podman binary or containers stack.

Everything that served the in-pod store is gone: `storage.conf`,
`fuse-overlayfs`, `/dev/fuse`, `SYS_ADMIN`/`MKNOD`.

## Consequences

- The import pays one extra copy of the image through the socket: Java sends the
  same archive body as remote `podman load` (`tunnel/images.go:222` plus
  `internal/localapi/utils.go:141`). `ctr images import` and `portoctl layer -I`
  already pay exactly that, which is what makes the arms comparable across
  engines.
- Prefix OCI layouts are tarred and streamed through the same load endpoint, so
  `app.tempDirectory` can be an `emptyDir`; the node no longer needs a matching
  hostPath.
- A node-side engine resolves names in the host netns, where there is no cluster
  resolver, so the registry address is turned into a ClusterIP
  (`riid_registry_node_host`), once per run rather than per image.
- dfinit edits the node's `/etc/containers/registries.conf` — the very file the
  daemon reads. The baseline can no longer be diverted to a private copy through
  `CONTAINERS_REGISTRIES_CONF`, so each arm asserts its own mirror state from
  `podman info` and fails loudly.
- `install-podman-node` must precede `install-riid`: the socket is mounted with
  `type: Socket`, so without it the pods never start.
