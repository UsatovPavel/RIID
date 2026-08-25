package riid.runtime.adapter;

import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
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

/**
 * Podman adapter (WSL2-friendly): {@code podman load -q -i path} for a file;
 * piped layout import uses {@code podman load -q} (stdin is the default input
 * per {@code podman load --help}).
 *
 * <p>
 * Optionally imports a growing prefix of the image while the tail still
 * downloads - see {@link #supportsIncrementalImport(Manifest)}.
 */
public class PodmanRuntimeAdapter implements RuntimeAdapter {
    public static final String PODMAN_BIN = "podman";
    private static final Logger LOGGER = LoggerFactory.getLogger(PodmanRuntimeAdapter.class);
    private static final int MAX_PROC_STDERR = 64 * 1024;
    private static final String PREFIX_REPOSITORY = "localhost/riid-prefix-";

    private final int prefixImportStride;

    public PodmanRuntimeAdapter() {
        this(DEFAULT_PREFIX_IMPORT_STRIDE);
    }

    /**
     * @param prefixImportStride
     *            how many layers to accumulate before handing the prefix to podman;
     *            {@link #PREFIX_IMPORT_OFF} keeps the single import of the whole
     *            image.
     */
    public PodmanRuntimeAdapter(int prefixImportStride) {
        this.prefixImportStride = Math.max(PREFIX_IMPORT_OFF, prefixImportStride);
    }

    @Override
    public RuntimeId runtimeId() {
        return RuntimeId.PODMAN;
    }

    @Override
    public boolean prefersOciLayoutStreamImport() {
        // false: `-i <path>` skips podman load's stdin-only io.Copy(tempfile, stdin)
        // step (cmd/podman/images/load.go) entirely. ~6% faster handoff, confirmed
        // over 4 independent fresh-Dragonfly-install A/B rounds, see bench_log.md.
        return false;
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
     * Streams {@code tar -cf - -C layout .} into {@code podman load -q} on stdin
     * (no {@code -i -}; that is a bogus path).
     */
    @Override
    public void importOciLayoutDirectory(Path ociLayoutRoot) throws IOException, InterruptedException {
        Objects.requireNonNull(ociLayoutRoot, "ociLayoutRoot");
        Path root = ociLayoutRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("OCI layout root is not a directory: " + root);
        }

        List<String> tarCmd = List.of("tar", "-cf", "-", "-C", root.toString(), ".");
        List<String> loadCmd = List.of(PODMAN_BIN, "load", "-q");
        BoundedCommandExecution.PipedShellResult result = BoundedCommandExecution.runWithStdoutPipedToStdin(tarCmd,
                loadCmd, MAX_PROC_STDERR, this::startProcess);
        result.throwIfFailed("tar", "podman load");
    }

    /**
     * Podman has no per-layer import command, so a prefix is handed over as a whole
     * small image built from the layers that already arrived. Nothing is
     * re-extracted: {@code containers/storage} keys a layer by chain-id
     * ({@code storage_dest.go:1043}) and reuses one it already holds, so every
     * prefix costs only its new top layers and the final import costs almost
     * nothing.
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
        return new PodmanIncrementalImport(imageName, manifest);
    }

    /**
     * Imports {@code {L0}}, then {@code {L0,L1}}, ... as the layers land, and the
     * real image once the last one is in. Intermediate images are named after the
     * session and dropped in {@link #finish()}.
     */
    private final class PodmanIncrementalImport implements IncrementalImageImport {
        private final String reference;
        private final PrefixImportLayouts layouts;
        private final List<String> prefixImages = new ArrayList<>();
        private final String sessionId = UUID.randomUUID().toString().substring(0, 8);
        private boolean finished;

        private PodmanIncrementalImport(String reference, Manifest manifest) {
            this.reference = reference;
            this.layouts = new PrefixImportLayouts(reference, manifest);
        }

        @Override
        public void importLayer(Descriptor layer, Path blobPath) throws IOException, InterruptedException {
            layouts.takeLayer(layer, blobPath);
            int count = layouts.layersTaken();
            if (count < layouts.layersExpected() && count % prefixImportStride == 0) {
                String image = PREFIX_REPOSITORY + sessionId + ":" + count;
                pull(layouts.prefixLayout(count, image, PrefixImportLayouts.LayerScope.ALL, 0), image);
                prefixImages.add(image);
                LOGGER.info("Prefix of {} layers handed to podman as {}", count, image);
            }
        }

        @Override
        public void finish() throws IOException, InterruptedException {
            if (layouts.layersTaken() != layouts.layersExpected()) {
                throw new IOException("Prefix import of " + reference + " finished with " + layouts.layersTaken()
                        + " of " + layouts.layersExpected() + " layers");
            }
            String image = PrefixImportLayouts.localReference(reference);
            pull(layouts.fullLayout(image, PrefixImportLayouts.LayerScope.ALL, 0), image);
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

        private void pull(Path layout, String image) throws IOException, InterruptedException {
            List<String> cmd = List.of(PODMAN_BIN, "pull", "-q", "oci:" + layout.toAbsolutePath() + ":" + image);
            BoundedCommandExecution.ShellResult result = runCommand(cmd);
            if (result.exitCode() != 0) {
                throw new IOException("podman pull of " + image + " failed (exit " + result.exitCode() + "): "
                        + result.stdout() + result.stderr());
            }
        }

        private void dropPrefixImages() throws IOException, InterruptedException {
            if (prefixImages.isEmpty()) {
                return;
            }
            List<String> cmd = new ArrayList<>(List.of(PODMAN_BIN, "rmi", "-f"));
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
