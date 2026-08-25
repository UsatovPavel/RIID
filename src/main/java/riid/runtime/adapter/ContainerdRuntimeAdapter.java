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
 * containerd adapter: {@code ctr images import path} for a file, or a tar
 * streamed into {@code ctr images import -}. Its OCI v1 importer keeps an
 * existing {@code org.opencontainers.image.ref.name} annotation, so RIID-built
 * archives need no {@code --base-name}.
 */
public class ContainerdRuntimeAdapter implements RuntimeAdapter {
    private static final String CTR_BIN = RuntimeId.CONTAINERD.bin();
    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerdRuntimeAdapter.class);
    private static final int MAX_PROC_STDERR = 64 * 1024;
    private static final String IMAGES = "images";
    private static final String IMPORT = "import";
    private static final String LOCAL = "--local";
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
    /** Extra {@code ctr images import} switches; all off unless configured. */
    private final ImportOptions options;
    /**
     * Hand containerd each layer as it lands, instead of the whole image at the
     * end.
     */
    private final boolean prefixImport;

    /**
     * Optional {@code ctr images import} switches. {@code snapshotter} null means
     * {@code ctr}'s own default (host-configured).
     */
    public record ImportOptions(String snapshotter, boolean discardUnpackedLayers, boolean prefixNoUnpack) {
        public static ImportOptions defaults() {
            return new ImportOptions(null, false, false);
        }
    }

    public ContainerdRuntimeAdapter() {
        this(PREFIX_IMPORT_ENABLED_BY_DEFAULT);
    }

    /**
     * @param prefixImport
     *            hand containerd each layer as it lands, instead of the whole image
     *            at the end
     */
    public ContainerdRuntimeAdapter(boolean prefixImport) {
        this(CTR_BIN, null, null, ImportOptions.defaults(), prefixImport);
    }

    public ContainerdRuntimeAdapter(String ctrCmd, String namespace, String address, String snapshotter) {
        this(ctrCmd, namespace, address, new ImportOptions(snapshotter, false, false),
                PREFIX_IMPORT_ENABLED_BY_DEFAULT);
    }

    public ContainerdRuntimeAdapter(String ctrCmd, String namespace, String address, ImportOptions options,
            boolean prefixImport) {
        this.ctrCmd = ctrCmd == null || ctrCmd.isBlank() ? CTR_BIN : ctrCmd;
        this.namespace = namespace;
        this.address = address;
        this.options = options == null ? ImportOptions.defaults() : options;
        this.prefixImport = prefixImport;
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
        importOciLayoutDirectory(ociLayoutRoot, false);
    }

    /**
     * @param prefixStep
     *            an intermediate prefix import, the only one allowed to skip
     *            unpacking
     */
    protected void importOciLayoutDirectory(Path ociLayoutRoot, boolean prefixStep)
            throws IOException, InterruptedException {
        Objects.requireNonNull(ociLayoutRoot, "ociLayoutRoot");
        Path root = ociLayoutRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("OCI layout root is not a directory: " + root);
        }

        List<String> tarCmd = List.of("tar", "-cf", "-", "-C", root.toString(), ".");
        List<String> importCmd = importCommand(prefixStep);
        importCmd.add("-");
        BoundedCommandExecution.PipedShellResult result = BoundedCommandExecution.runWithStdoutPipedToStdin(tarCmd,
                importCmd, MAX_PROC_STDERR, this::startProcess);
        result.throwIfFailed("tar", "ctr images import");
    }

    /**
     * containerd unpacks a layer into a snapshot keyed by chain-id, so a prefix it
     * already holds is reused rather than applied again.
     */
    @Override
    public boolean supportsIncrementalImport(Manifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        // A prefix tar carries only the layers added since the previous one: the
        // importer ingests what the tar contains and never checks that every
        // referenced blob is there (core/images/archive/importer.go), so the rest
        // resolves from the content store and the bytes streamed stay linear.
        return prefixImport && manifest.layers().size() > 1;
    }

    @Override
    public IncrementalImageImport beginIncrementalImport(ImageReference image, Manifest manifest) throws IOException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(manifest, "manifest");
        if (!supportsIncrementalImport(manifest)) {
            throw new IOException("Image " + image + " is not imported by prefix: prefixImport=" + prefixImport + ", "
                    + manifest.layers().size() + " layers");
        }
        return new ContainerdIncrementalImport(image, manifest);
    }

    /**
     * Imports {@code {L0}}, then {@code {L0,L1}}, ... as the layers land, and the
     * real image once the last one is in - named exactly as an ordinary import
     * would name it, the ref.name annotation being kept.
     */
    private final class ContainerdIncrementalImport implements IncrementalImageImport {
        private final ImageReference reference;
        private final PrefixImportLayouts layouts;
        private final List<String> prefixImages = new ArrayList<>();
        private final String sessionId = UUID.randomUUID().toString().substring(0, 8);
        private int sentLayers;
        private boolean finished;

        private ContainerdIncrementalImport(ImageReference reference, Manifest manifest) {
            this.reference = reference;
            this.layouts = new PrefixImportLayouts(reference.name(), manifest);
        }

        @Override
        public void imageConfig(Path configBlob) throws IOException {
            layouts.takeImageConfig(configBlob);
        }

        @Override
        public void importLayer(Descriptor layer, Path blobPath) throws IOException, InterruptedException {
            layouts.takeLayer(layer, blobPath);
            int count = layouts.layersTaken();
            if (count < layouts.layersExpected()) {
                String image = PREFIX_REPOSITORY + sessionId + ":" + count;
                importLayout(layouts.prefixLayout(count, image, PrefixImportLayouts.LayerScope.ADDED_ONLY, sentLayers),
                        true);
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
            importLayout(layouts.fullLayout(reference.name(), PrefixImportLayouts.LayerScope.ADDED_ONLY, sentLayers),
                    false);
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

        private void importLayout(Path layout, boolean prefixStep) throws IOException, InterruptedException {
            importOciLayoutDirectory(layout, prefixStep);
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
        return importCommand(false);
    }

    /**
     * @param prefixStep
     *            an intermediate prefix import, the only one allowed to skip
     *            unpacking
     */
    private List<String> importCommand(boolean prefixStep) {
        List<String> cmd = imagesCommand(IMPORT);
        String snapshotter = options.snapshotter();
        if (snapshotter != null && !snapshotter.isBlank()) {
            cmd.add("--snapshotter");
            cmd.add(snapshotter);
        }
        // ctr refuses --no-unpack together with --discard-unpacked-layers, so the
        // two are chosen here rather than combined: a prefix that is not unpacked
        // has nothing to discard, and the final import always unpacks.
        if (prefixStep && options.prefixNoUnpack()) {
            cmd.add("--no-unpack");
        } else if (options.discardUnpackedLayers()) {
            // ctr 2.2 rejects the flag on its own: discarding is only implemented by
            // the local importer, not by the transfer service the import defaults to.
            cmd.add(LOCAL);
            cmd.add("--discard-unpacked-layers");
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
