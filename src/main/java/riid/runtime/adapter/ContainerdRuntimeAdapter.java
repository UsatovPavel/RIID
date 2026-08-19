package riid.runtime.adapter;

import riid.runtime.BoundedCommandExecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * containerd adapter: {@code ctr images import path} for a file; piped layout
 * import streams a tar into {@code ctr images import -} (stdin, per
 * {@code ctr images import --help}). containerd's OCI v1 importer keeps an
 * existing {@code org.opencontainers.image.ref.name} annotation untouched, so
 * RIID-built archives (see {@code OciArchiveBuilder}) do not need
 * {@code --base-name}.
 */
public class ContainerdRuntimeAdapter implements RuntimeAdapter {
    public static final String CTR_BIN = "ctr";
    private static final int MAX_PROC_STDERR = 64 * 1024;
    private static final String IMAGES = "images";
    private static final String IMPORT = "import";

    /** Path/name of the {@code ctr} binary, for non-default installs. */
    private final String ctrCmd;
    /** {@code -n}: containerd namespace; null uses {@code ctr}'s own default ({@code default}). */
    private final String namespace;
    /** {@code -a}: daemon socket address; null uses {@code ctr}'s own default
     * ({@code /run/containerd/containerd.sock}). */
    private final String address;
    /** {@code --snapshotter}: snapshotter backend; null uses {@code ctr}'s own default (host-configured). */
    private final String snapshotter;

    public ContainerdRuntimeAdapter() {
        this(CTR_BIN, null, null, null);
    }

    public ContainerdRuntimeAdapter(String ctrCmd, String namespace, String address, String snapshotter) {
        this.ctrCmd = ctrCmd == null || ctrCmd.isBlank() ? CTR_BIN : ctrCmd;
        this.namespace = namespace;
        this.address = address;
        this.snapshotter = snapshotter;
    }

    @Override
    public RuntimeId runtimeId() {
        return RuntimeId.CONTAINERD;
    }

    @Override
    public boolean prefersOciLayoutStreamImport() {
        // true, unlike Podman's `-i <path>` (see PodmanRuntimeAdapter): that win comes
        // from skipping Podman's own stdin->tempfile copy. `ctr images import` has no
        // such copy to skip — file or stdin, it always proxies through the
        // transfer/streaming gRPC service (core/transfer/archive), decoded by an
        // already single-pass tar.Reader (core/images/archive/importer.go). A path
        // here would only add a real disk write that streaming avoids.
        return true;
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        Objects.requireNonNull(imagePath, "imagePath");
        if (!imagePath.toFile().exists()) {
            throw new IOException("Image file not found: " + imagePath);
        }

        List<String> cmd = importCommand();
        cmd.add(imagePath.toAbsolutePath().toString());
        BoundedCommandExecution.ShellResult shellResult = runCommand(cmd);
        if (shellResult.exitCode() != 0) {
            throw new IOException("ctr images import failed (exit " + shellResult.exitCode() + "): "
                    + shellResult.stdout() + shellResult.stderr());
        }
    }

    /**
     * Streams {@code tar -cf - -C layout .} into {@code ctr images import -}
     * (stdin, per {@code ctr images import --help}).
     */
    @Override
    public void importOciLayoutDirectory(Path ociLayoutRoot) throws IOException, InterruptedException {
        Objects.requireNonNull(ociLayoutRoot, "ociLayoutRoot");
        Path root = ociLayoutRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("OCI layout root is not a directory: " + root);
        }

        List<String> tarCmd = List.of("tar", "-cf", "-", "-C", root.toString(), ".");
        List<String> importCmd = importCommand();
        importCmd.add("-");
        BoundedCommandExecution.PipedShellResult result = BoundedCommandExecution.runWithStdoutPipedToStdin(tarCmd,
                importCmd, MAX_PROC_STDERR, this::startProcess);
        result.throwIfFailed("tar", "ctr images import");
    }

    private List<String> importCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(ctrCmd);
        if (address != null && !address.isBlank()) {
            cmd.add("-a");
            cmd.add(address);
        }
        if (namespace != null && !namespace.isBlank()) {
            cmd.add("-n");
            cmd.add(namespace);
        }
        cmd.add(IMAGES);
        cmd.add(IMPORT);
        if (snapshotter != null && !snapshotter.isBlank()) {
            cmd.add("--snapshotter");
            cmd.add(snapshotter);
        }
        return cmd;
    }

    /**
     * Hook for tests to override process creation.
     */
    protected Process startProcess(List<String> command) throws IOException {
        return new ProcessBuilder(command).start();
    }

    protected BoundedCommandExecution.ShellResult runCommand(List<String> command)
            throws IOException, InterruptedException {
        return BoundedCommandExecution.run(command);
    }
}
