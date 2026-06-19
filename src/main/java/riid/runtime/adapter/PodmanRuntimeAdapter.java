package riid.runtime.adapter;

import riid.runtime.BoundedCommandExecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Podman adapter (WSL2-friendly): {@code podman load -q -i path} for a file;
 * OCI layout directories are imported via the {@code oci:} transport
 * ({@code podman pull oci:
 *
<dir>
 * :<reference>}), which reads blobs straight off disk instead of re-packing
 * them into a tar stream for {@code podman load}. Storage driver (native
 * overlay vs. {@code fuse-overlayfs}) is a deploy-time decision in
 * {@code storage.conf}, not something this adapter probes or retries at
 * runtime.
 */
public class PodmanRuntimeAdapter implements RuntimeAdapter {
    public static final String PODMAN_BIN = "podman";

    @Override
    public String runtimeId() {
        return "podman";
    }

    @Override
    public boolean prefersOciLayoutStreamImport() {
        return true;
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        Objects.requireNonNull(imagePath, "imagePath");
        if (!imagePath.toFile().exists()) {
            throw new IOException("Image file not found: " + imagePath);
        }

        List<String> cmd = List.of(PODMAN_BIN, "load", "-q", "-i", imagePath.toAbsolutePath().toString());
        BoundedCommandExecution.ShellResult shellResult = runCommand(cmd);
        if (shellResult.exitCode() != 0) {
            throw new IOException("podman load failed (exit " + shellResult.exitCode() + "): " + shellResult.stdout()
                    + shellResult.stderr());
        }
    }

    /**
     * Imports straight from the on-disk OCI layout via {@code podman pull
     * oci:
    <dir>
    :<reference>} (no tar packing/unpacking round-trip), then tags the result with
     * {@code reference} in case the {@code oci:} transport named the image by
     * digest instead of by the {@code org.opencontainers.image.ref.name}
     * annotation.
     */
    @Override
    public void importOciLayoutDirectory(Path ociLayoutRoot, String reference)
            throws IOException, InterruptedException {
        Objects.requireNonNull(ociLayoutRoot, "ociLayoutRoot");
        Objects.requireNonNull(reference, "reference");
        Path root = ociLayoutRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("OCI layout root is not a directory: " + root);
        }

        List<String> pullCmd = List.of(PODMAN_BIN, "pull", "-q", "oci:" + root + ":" + reference);
        BoundedCommandExecution.ShellResult pullResult = runCommand(pullCmd);
        if (pullResult.exitCode() != 0) {
            throw new IOException("podman pull failed (exit " + pullResult.exitCode() + "): " + pullResult.stdout()
                    + pullResult.stderr());
        }

        String pulledId = pullResult.stdout().trim();
        String tagSource = pulledId.isEmpty() ? reference : pulledId;
        List<String> tagCmd = List.of(PODMAN_BIN, "tag", tagSource, reference);
        BoundedCommandExecution.ShellResult tagResult = runCommand(tagCmd);
        if (tagResult.exitCode() != 0) {
            throw new IOException("podman tag failed (exit " + tagResult.exitCode() + "): " + tagResult.stdout()
                    + tagResult.stderr());
        }
    }

    protected BoundedCommandExecution.ShellResult runCommand(List<String> command)
            throws IOException, InterruptedException {
        return BoundedCommandExecution.run(command);
    }
}
