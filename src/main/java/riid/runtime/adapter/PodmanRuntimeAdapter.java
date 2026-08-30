package riid.runtime.adapter;

import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.core.fs.HostFilesystem;
import riid.core.fs.NioHostFilesystem;
import riid.runtime.BoundedCommandExecution;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Podman adapter. In Kubernetes it streams archives to the Libpod API over the
 * Unix socket from {@code CONTAINER_HOST}; when that variable is empty it falls
 * back to the local {@code podman} CLI. CLI mode can import a growing prefix
 * while the tail downloads, see {@link #supportsIncrementalImport(Manifest)}.
 */
public class PodmanRuntimeAdapter implements RuntimeAdapter {
    private static final String PODMAN_BIN = RuntimeId.PODMAN.bin();
    private static final Logger LOGGER = LoggerFactory.getLogger(PodmanRuntimeAdapter.class);
    private static final int MAX_PROC_STDERR = 64 * 1024;
    private static final String PREFIX_REPOSITORY = "localhost/riid-prefix-";
    private static final String EXIT_ERROR_SEPARATOR = "): ";

    private final HostFilesystem fs;
    private final boolean prefixImport;
    private final Optional<PodmanUnixSocketClient> socketClient;

    public PodmanRuntimeAdapter() {
        this(new NioHostFilesystem(), PREFIX_IMPORT_ENABLED_BY_DEFAULT);
    }

    /**
     * @param prefixImport
     *            hand podman each layer as it lands, instead of the whole image at
     *            the end
     */
    public PodmanRuntimeAdapter(boolean prefixImport) {
        this(new NioHostFilesystem(), prefixImport);
    }

    public PodmanRuntimeAdapter(HostFilesystem fs, boolean prefixImport) {
        this(fs, prefixImport, PodmanUnixSocketClient.fromEnvironment(fs));
    }

    /**
     * Package-private seam for socket contract tests. Production resolves the
     * endpoint in {@link PodmanUnixSocketClient#fromEnvironment(HostFilesystem)}.
     */
    PodmanRuntimeAdapter(HostFilesystem fs, boolean prefixImport, Optional<PodmanUnixSocketClient> socketClient) {
        this.fs = Objects.requireNonNull(fs, "fs");
        this.prefixImport = prefixImport;
        this.socketClient = Objects.requireNonNull(socketClient, "socketClient");
    }

    @Override
    public RuntimeId runtimeId() {
        return RuntimeId.PODMAN;
    }

    @Override
    public boolean prefersOciLayoutStreamImport() {
        // The socket endpoint only accepts a tar stream. Avoid first materializing
        // the same OCI layout as a second archive on the client filesystem.
        return socketClient.isPresent();
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        Objects.requireNonNull(imagePath, "imagePath");
        if (!fs.exists(imagePath) || !fs.isRegularFile(imagePath)) {
            throw new IOException("Image file not found: " + imagePath);
        }

        Path archive = imagePath.toAbsolutePath();
        if (socketClient.isPresent()) {
            socketClient.get().loadArchive(archive);
            return;
        }

        List<String> cmd = List.of(PODMAN_BIN, "load", "-q", "-i", archive.toString());
        BoundedCommandExecution.ShellResult shellResult = runCommand(cmd);
        if (shellResult.exitCode() != 0) {
            throw new IOException("podman load failed (exit " + shellResult.exitCode() + EXIT_ERROR_SEPARATOR
                    + shellResult.stdout() + shellResult.stderr());
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
        if (!fs.isDirectory(root)) {
            throw new IOException("OCI layout root is not a directory: " + root);
        }

        List<String> tarCmd = List.of("tar", "-cf", "-", "-C", root.toString(), ".");
        if (socketClient.isPresent()) {
            BoundedCommandExecution.StreamedShellResult result = BoundedCommandExecution.runWithStdoutConsumer(tarCmd,
                    MAX_PROC_STDERR, this::startProcess, socketClient.orElseThrow()::loadArchive);
            result.throwIfFailed("tar");
            return;
        }

        List<String> loadCmd = List.of(PODMAN_BIN, "load", "-q");
        BoundedCommandExecution.PipedShellResult result = BoundedCommandExecution.runWithStdoutPipedToStdin(tarCmd,
                loadCmd, MAX_PROC_STDERR, this::startProcess);
        result.throwIfFailed("tar", "podman load");
    }

    /**
     * Podman has no per-layer import command, so a prefix is handed over as a whole
     * small image built from the layers that already arrived.
     */
    @Override
    public boolean supportsIncrementalImport(Manifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        // Nothing is re-extracted: containers/storage keys a layer by chain-id
        // (storage_dest.go:1043) and reuses one it already holds, so a prefix costs
        // only its new top layers and the final import costs almost nothing.
        // Sending every accumulated prefix through images/load would retransmit
        // all earlier layers and make socket traffic and server temp writes O(N²).
        return socketClient.isEmpty() && prefixImport && manifest.layers().size() > 1;
    }

    @Override
    public IncrementalImageImport beginIncrementalImport(ImageReference image, Manifest manifest) throws IOException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(manifest, "manifest");
        if (!supportsIncrementalImport(manifest)) {
            throw new IOException(
                    "Image " + image + " is not imported by prefix: socketClient=" + socketClient.isPresent()
                            + ", prefixImport=" + prefixImport + ", " + manifest.layers().size() + " layers");
        }
        return new PodmanIncrementalImport(image, manifest);
    }

    /**
     * Imports {@code {L0}}, then {@code {L0,L1}}, ... as the layers land, and the
     * real image once the last one is in. Intermediate images are named after the
     * session and dropped in {@link #finish()}.
     */
    private final class PodmanIncrementalImport implements IncrementalImageImport {
        private final ImageReference reference;
        private final PrefixImportLayouts layouts;
        private final List<String> prefixImages = new ArrayList<>();
        private final String sessionId = UUID.randomUUID().toString().substring(0, 8);
        private boolean finished;

        private PodmanIncrementalImport(ImageReference reference, Manifest manifest) {
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
            // podman resolves an unqualified name against Docker Hub; `podman load`
            // qualifies it with localhost/, and prefix import must name it the same.
            String image = reference.localName();
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
                throw new IOException("podman pull of " + image + " failed (exit " + result.exitCode()
                        + EXIT_ERROR_SEPARATOR + result.stdout() + result.stderr());
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

    @Override
    public void close() throws IOException {
        if (socketClient.isPresent()) {
            socketClient.get().close();
        }
    }
}
