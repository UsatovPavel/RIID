package riid.runtime.adapter;

import riid.runtime.BoundedCommandExecution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerdRuntimeAdapter.class);
    private static final int MAX_PROC_STDERR = 64 * 1024;
    private static final String IMAGES = "images";
    private static final String IMPORT = "import";
    private static final String PREFIX_REPOSITORY = "riid-prefix-";

    /** Path/name of the {@code ctr} binary, for non-default installs. */
    private final String ctrCmd;
    /**
     * {@code -n}: containerd namespace; null uses {@code ctr}'s own default
     * ({@code default}).
     */
    private final String namespace;
    /**
     * {@code -a}: daemon socket address; null uses {@code ctr}'s own default
     * ({@code /run/containerd/containerd.sock}).
     */
    private final String address;
    /**
     * {@code --snapshotter}: snapshotter backend; null uses {@code ctr}'s own
     * default (host-configured).
     */
    private final String snapshotter;
    /**
     * How many layers to accumulate before handing the prefix over; 0 keeps the
     * single import.
     */
    private final int prefixImportStride;

    public ContainerdRuntimeAdapter() {
        this(CTR_BIN, null, null, null);
    }

    public ContainerdRuntimeAdapter(String ctrCmd, String namespace, String address, String snapshotter) {
        this(ctrCmd, namespace, address, snapshotter, DEFAULT_PREFIX_IMPORT_STRIDE);
    }

    public ContainerdRuntimeAdapter(String ctrCmd, String namespace, String address, String snapshotter,
            int prefixImportStride) {
        this.ctrCmd = ctrCmd == null || ctrCmd.isBlank() ? CTR_BIN : ctrCmd;
        this.namespace = namespace;
        this.address = address;
        this.snapshotter = snapshotter;
        this.prefixImportStride = Math.max(PREFIX_IMPORT_OFF, prefixImportStride);
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

    /**
     * containerd unpacks a layer into a snapshot keyed by chain-id, so a prefix
     * already unpacked is reused rather than applied again. Unlike podman, its
     * importer reads a tar - but it ingests only what the tar contains and never
     * checks that every referenced blob is there
     * ({@code core/images/archive/importer.go}), so a prefix tar carries only the
     * layers added since the previous one and the rest is resolved from the content
     * store. That keeps the bytes streamed linear in image size instead of
     * quadratic.
     */
    @Override
    public boolean supportsIncrementalImport(Manifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        return prefixImportStride > PREFIX_IMPORT_OFF && manifest.layers().size() > prefixImportStride;
    }

    @Override
    public IncrementalImageImport beginIncrementalImport(String imageName, Manifest manifest) throws IOException {
        Objects.requireNonNull(imageName, "imageName");
        Objects.requireNonNull(manifest, "manifest");
        if (!supportsIncrementalImport(manifest)) {
            throw new IOException("Image " + imageName + " is not worth importing by prefix: stride "
                    + prefixImportStride + ", " + manifest.layers().size() + " layers");
        }
        return new ContainerdIncrementalImport(imageName, manifest);
    }

    /**
     * Imports {@code {L0}}, then {@code {L0,L1}}, ... as the layers land, and the
     * real image once the last one is in. containerd keeps the
     * {@code org.opencontainers.image.ref.name} annotation untouched, so the image
     * ends up named exactly as the ordinary import names it.
     */
    private final class ContainerdIncrementalImport implements IncrementalImageImport {
        private final String reference;
        private final PrefixImportLayouts layouts;
        private final List<String> prefixImages = new ArrayList<>();
        private final String sessionId = UUID.randomUUID().toString().substring(0, 8);
        private int sentLayers;
        private boolean finished;

        private ContainerdIncrementalImport(String reference, Manifest manifest) {
            this.reference = reference;
            this.layouts = new PrefixImportLayouts(reference, manifest);
        }

        @Override
        public void importLayer(Descriptor layer, Path blobPath) throws IOException, InterruptedException {
            layouts.takeLayer(layer, blobPath);
            int count = layouts.layersTaken();
            if (count < layouts.layersExpected() && count % prefixImportStride == 0) {
                String image = PREFIX_REPOSITORY + sessionId + ":" + count;
                importLayout(layouts.prefixLayout(count, image, PrefixImportLayouts.LayerScope.ADDED_ONLY, sentLayers));
                sentLayers = count;
                prefixImages.add(image);
                LOGGER.info("Prefix of {} layers handed to containerd as {}", count, image);
            }
        }

        @Override
        public void finish() throws IOException, InterruptedException {
            if (layouts.layersTaken() != layouts.layersExpected()) {
                throw new IOException("Prefix import of " + reference + " finished with " + layouts.layersTaken()
                        + " of " + layouts.layersExpected() + " layers");
            }
            importLayout(layouts.fullLayout(reference, PrefixImportLayouts.LayerScope.ADDED_ONLY, sentLayers));
            finished = true;
            dropPrefixImages();
        }

        @Override
        public void close() throws IOException {
            if (!finished) {
                LOGGER.warn("Prefix import of {} aborted after {} of {} layers; no image published", reference,
                        layouts.layersTaken(), layouts.layersExpected());
                dropPrefixImagesQuietly();
            }
            layouts.close();
        }

        private void importLayout(Path layout) throws IOException, InterruptedException {
            importOciLayoutDirectory(layout);
        }

        private void dropPrefixImages() throws IOException, InterruptedException {
            if (prefixImages.isEmpty()) {
                return;
            }
            List<String> cmd = imagesCommand("rm");
            cmd.addAll(prefixImages);
            BoundedCommandExecution.ShellResult result = runCommand(cmd);
            if (result.exitCode() != 0) {
                // The layers survive in the store under the image that was just imported.
                LOGGER.warn("Could not drop intermediate prefix images {} (exit {}): {}", prefixImages,
                        result.exitCode(), result.stderr());
            }
        }

        private void dropPrefixImagesQuietly() {
            try {
                dropPrefixImages();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                LOGGER.warn("Could not drop intermediate prefix images {}: {}", prefixImages, e.toString());
            }
        }
    }

    private List<String> importCommand() {
        List<String> cmd = imagesCommand(IMPORT);
        if (snapshotter != null && !snapshotter.isBlank()) {
            cmd.add("--snapshotter");
            cmd.add(snapshotter);
        }
        return cmd;
    }

    private List<String> imagesCommand(String subcommand) {
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
        cmd.add(subcommand);
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
