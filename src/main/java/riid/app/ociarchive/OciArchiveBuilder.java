package riid.app.ociarchive;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;

import riid.app.core.model.ImageId;
import riid.app.core.error.AppError;
import riid.app.core.error.OciArchiveException;
import riid.core.fs.HostFilesystem;
import riid.cache.oci.ImageDigest;
import riid.core.hash.Sha256Utils;
import riid.core.fs.PathSupport;
import riid.client.api.ManifestResult;
import riid.core.model.manifest.Descriptor;
import riid.core.model.manifest.Manifest;
import riid.core.model.manifest.MediaType;
import riid.core.model.manifest.MediaTypes;
import riid.core.model.manifest.OciLayout;
import riid.core.logging.MdcContext;
import riid.core.logging.MilestoneEventLogger;
import riid.core.logging.MilestoneEventLogger.EventType;
import riid.core.logging.MilestoneEventLogger.ResultType;
import riid.dispatcher.RequestDispatcher;
import riid.dispatcher.model.RepositoryName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Builds an OCI archive from a manifest, pulling blobs via RequestDispatcher.
 */
public final class OciArchiveBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(OciArchiveBuilder.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final BlobArrival IGNORE_ARRIVALS = (digestHex, blobPath) -> {
    };

    private final RequestDispatcher dispatcher;
    private final HostFilesystem fs;
    private final Path tempRoot;

    public OciArchiveBuilder(RequestDispatcher dispatcher, HostFilesystem fs) {
        this(dispatcher, fs, null);
    }

    public OciArchiveBuilder(RequestDispatcher dispatcher, HostFilesystem fs, Path tempRoot) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.fs = Objects.requireNonNull(fs, "fs");
        this.tempRoot = tempRoot;
    }

    public <T> T withArchive(ImageId imageId, ManifestResult manifestResult, ArchiveUser<T> user)
            throws IOException, InterruptedException {
        String previousOperation = MdcContext.getOperation();
        MdcContext.putOperation(EventType.ARCHIVE_BUILD.value());
        long startedNs = System.nanoTime();
        try (OciArchive archive = build(imageId, manifestResult)) {
            MilestoneEventLogger.info(LOGGER).addEvent(EventType.ARCHIVE_BUILD).addResult(ResultType.SUCCESS)
                    .addDurationMs(durationMs(startedNs)).log("OCI archive build completed");
            return user.use(archive.archivePath());
        } catch (IOException | InterruptedException | RuntimeException e) {
            MilestoneEventLogger.error(LOGGER).addCause(e).addEvent(EventType.ARCHIVE_BUILD).addResult(ResultType.ERROR)
                    .addDurationMs(durationMs(startedNs)).addErrorKind("INTERNAL").addErrorCode("ARCHIVE_BUILD_FAILED")
                    .log("OCI archive build failed");
            throw e;
        } finally {
            MdcContext.restoreOperation(previousOperation);
        }
    }

    /**
     * Build OCI layout on disk, then consume it without creating an oci-archive tar
     * file (for streaming import).
     */
    public <T> T withOciLayout(ImageId imageId, ManifestResult manifestResult, LayoutUser<T> user)
            throws IOException, InterruptedException {
        String previousOperation = MdcContext.getOperation();
        MdcContext.putOperation(EventType.ARCHIVE_BUILD.value());
        long startedNs = System.nanoTime();
        try (OciArchive workspace = buildLayoutWorkspace(imageId, manifestResult)) {
            MilestoneEventLogger.info(LOGGER).addEvent(EventType.ARCHIVE_BUILD).addResult(ResultType.SUCCESS)
                    .addDurationMs(durationMs(startedNs))
                    .log("OCI layout build completed (stream import, no tar file)");
            return user.use(workspace.ociDir());
        } catch (IOException | InterruptedException | RuntimeException e) {
            MilestoneEventLogger.error(LOGGER).addCause(e).addEvent(EventType.ARCHIVE_BUILD).addResult(ResultType.ERROR)
                    .addDurationMs(durationMs(startedNs)).addErrorKind("INTERNAL").addErrorCode("ARCHIVE_BUILD_FAILED")
                    .log("OCI layout build failed");
            throw e;
        } finally {
            MdcContext.restoreOperation(previousOperation);
        }
    }

    /**
     * Hands every layer to {@code sink} in manifest order as soon as it lands, so
     * the runtime imports the prefix already downloaded while the tail is still in
     * flight. The sink runs on the calling thread; the pulls run on virtual
     * threads, which is where the overlap comes from.
     */
    public void streamLayers(ImageId imageId, ManifestResult manifestResult, LayerSink sink)
            throws IOException, InterruptedException {
        Objects.requireNonNull(imageId, "imageId");
        Objects.requireNonNull(manifestResult, "manifestResult");
        Objects.requireNonNull(sink, "sink");
        String previousOperation = MdcContext.getOperation();
        MdcContext.putOperation(EventType.ARCHIVE_BUILD.value());
        long startedNs = System.nanoTime();
        try (OciArchive workspace = OciArchive.layoutOnly(createLayoutDirectory(), fs)) {
            streamIntoLayout(workspace.ociDir(), imageId, manifestResult, sink);
            MilestoneEventLogger.info(LOGGER).addEvent(EventType.ARCHIVE_BUILD).addResult(ResultType.SUCCESS)
                    .addDurationMs(durationMs(startedNs))
                    .log("OCI layout streamed to runtime layer by layer (incremental import)");
        } catch (IOException | InterruptedException | RuntimeException e) {
            MilestoneEventLogger.error(LOGGER).addCause(e).addEvent(EventType.ARCHIVE_BUILD).addResult(ResultType.ERROR)
                    .addDurationMs(durationMs(startedNs)).addErrorKind("INTERNAL").addErrorCode("ARCHIVE_BUILD_FAILED")
                    .log("OCI layout streaming failed");
            throw e;
        } finally {
            MdcContext.restoreOperation(previousOperation);
        }
    }

    /**
     * Logical payload bytes for load metrics and comparisons across import paths.
     * This is not tar stream size: {@code config.size + sum(layer.size) +
     * manifestBytes.length}.
     */
    public long estimatePayloadBytes(ManifestResult manifestResult) throws IOException {
        Objects.requireNonNull(manifestResult, "manifestResult");
        Manifest manifest = manifestResult.manifest();
        long configBytes = manifest.config().size();
        long layersBytes = manifest.layers().stream().mapToLong(Descriptor::size).sum();
        long manifestBytes = OBJECT_MAPPER.writeValueAsBytes(manifest).length;
        return configBytes + layersBytes + manifestBytes;
    }

    private OciArchive build(ImageId imageId, ManifestResult manifestResult) throws IOException, InterruptedException {
        Path ociDir = buildOciDirectory(imageId, manifestResult);
        Path archive = PathSupport.temporaryPath(tempRoot, "oci-archive-", ".tar");
        fs.createFile(archive);
        runTar(archive, ociDir);
        return OciArchive.withTar(archive, ociDir, fs);
    }

    private OciArchive buildLayoutWorkspace(ImageId imageId, ManifestResult manifestResult)
            throws IOException, InterruptedException {
        Path ociDir = buildOciDirectory(imageId, manifestResult);
        return OciArchive.layoutOnly(ociDir, fs);
    }

    private Path buildOciDirectory(ImageId imageId, ManifestResult manifestResult)
            throws IOException, InterruptedException {
        Objects.requireNonNull(imageId, "imageId");
        Objects.requireNonNull(manifestResult, "manifestResult");

        Manifest manifest = manifestResult.manifest();
        Path ociDir = createLayoutDirectory();
        Path blobsDir = blobsDir(ociDir);
        PullTaskPlanner pullTaskPlanner = planPulls(imageId, manifest, blobsDir, IGNORE_ARRIVALS);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = executor.invokeAll(pullTaskPlanner.pullTasks());
            for (Future<Void> future : futures) {
                awaitPull(future);
            }
        }

        writeLayoutMetadata(ociDir, blobsDir, imageId, manifestResult);
        return ociDir;
    }

    /**
     * Same downloads as {@link #buildOciDirectory}, minus the barrier: every pull
     * runs on its own virtual thread and this thread walks the manifest, importing
     * layer {@code k} the moment layers {@code 0..k} are on disk.
     */
    private void streamIntoLayout(Path ociDir, ImageId imageId, ManifestResult manifestResult, LayerSink sink)
            throws IOException, InterruptedException {
        Manifest manifest = manifestResult.manifest();
        Path blobsDir = blobsDir(ociDir);
        Map<String, CompletableFuture<Path>> arrivals = new ConcurrentHashMap<>();
        for (Descriptor blob : withConfig(manifest)) {
            arrivals.computeIfAbsent(ImageDigest.parse(blob.digest()).hex(), key -> new CompletableFuture<>());
        }
        PullTaskPlanner pullTaskPlanner = planPulls(imageId, manifest, blobsDir,
                (digestHex, blobPath) -> completeArrival(arrivals, digestHex, blobPath));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = new ArrayList<>(pullTaskPlanner.pullTasks().size());
            for (Callable<Void> task : pullTaskPlanner.pullTasks()) {
                futures.add(executor.submit(failArrivalsOnError(task, arrivals)));
            }
            try {
                sink.onImageConfig(awaitArrival(arrivals.get(ImageDigest.parse(manifest.config().digest()).hex())));
                importLayerPrefix(manifest, arrivals, sink);
                for (Future<Void> future : futures) {
                    awaitPull(future);
                }
            } catch (IOException | InterruptedException | RuntimeException e) {
                executor.shutdownNow();
                throw e;
            }
        }

        writeLayoutMetadata(ociDir, blobsDir, imageId, manifestResult);
    }

    /**
     * Walks the manifest in order, blocking on each layer only until that layer is
     * downloaded - so the runtime always holds the longest already-available prefix
     * of the image.
     */
    private static void importLayerPrefix(Manifest manifest, Map<String, CompletableFuture<Path>> arrivals,
            LayerSink sink) throws IOException, InterruptedException {
        for (Descriptor layer : manifest.layers()) {
            long waitStartedNs = System.nanoTime();
            Path blobPath = awaitArrival(arrivals.get(ImageDigest.parse(layer.digest()).hex()));
            long waitedMs = durationMs(waitStartedNs);
            long importStartedNs = System.nanoTime();
            sink.onLayer(layer, blobPath);
            MilestoneEventLogger.info(LOGGER).addEvent(EventType.LAYER_IMPORT).addResult(ResultType.SUCCESS)
                    .addDurationMs(durationMs(importStartedNs)).log("Layer " + layer.digest() + " imported ("
                            + layer.size() + " B, waited " + waitedMs + " ms for its download)");
        }
    }

    /**
     * The config blob is awaited like a layer, so nobody has to poll the disk for
     * it.
     */
    private static List<Descriptor> withConfig(Manifest manifest) {
        List<Descriptor> blobs = new ArrayList<>(manifest.layers());
        blobs.add(manifest.config());
        return blobs;
    }

    private Path createLayoutDirectory() throws IOException {
        Path ociDir = PathSupport.tempDirPath(tempRoot, "oci-layout-");
        fs.createDirectory(ociDir);
        fs.createDirectory(blobsDir(ociDir));
        return ociDir;
    }

    private static Path blobsDir(Path ociDir) {
        return ociDir.resolve("blobs").resolve("sha256");
    }

    private PullTaskPlanner planPulls(ImageId imageId, Manifest manifest, Path blobsDir, BlobArrival onArrival) {
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        PullTaskPlanner pullTaskPlanner = new PullTaskPlanner(1 + manifest.layers().size(), mdcSnapshot, imageId.name(),
                blobsDir, onArrival);
        var cfg = manifest.config();
        pullTaskPlanner.addIfDigestNew(cfg.digest(), cfg.size(), MediaType.from(cfg.mediaType()));
        for (var layer : manifest.layers()) {
            pullTaskPlanner.addIfDigestNew(layer.digest(), layer.size(), MediaType.from(layer.mediaType()));
        }
        return pullTaskPlanner;
    }

    /**
     * Writes what makes the downloaded blobs an OCI layout: the manifest blob,
     * {@code oci-layout} and {@code index.json}.
     */
    private void writeLayoutMetadata(Path ociDir, Path blobsDir, ImageId imageId, ManifestResult manifestResult)
            throws IOException {
        Manifest manifest = manifestResult.manifest();
        byte[] manifestBytes = OBJECT_MAPPER.writeValueAsBytes(manifest);
        String manifestDigest = Sha256Utils.digest(new ByteArrayInputStream(manifestBytes))
                .replace(OciLayout.DIGEST_PREFIX, "");
        fs.write(blobsDir.resolve(manifestDigest), manifestBytes);

        fs.writeString(ociDir.resolve(OciLayout.MARKER_FILE), OciLayout.MARKER_CONTENT);

        // index.json: descriptor mediaType must match the manifest blob (Docker v2 vs
        // OCI), or podman load fails.
        String template = readResource("oci/index/json.tpl");
        String indexMediaType = indexManifestMediaType(manifestResult, manifest);
        String index = String.format(Locale.ROOT, template, indexMediaType, manifestBytes.length, manifestDigest,
                imageId.referenceName());
        fs.writeString(ociDir.resolve(OciLayout.INDEX_JSON), index);
    }

    @FunctionalInterface
    public interface ArchiveUser<T> {
        T use(Path archivePath) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface LayoutUser<T> {
        T use(Path ociLayoutRoot) throws IOException, InterruptedException;
    }

    /** Consumer of layers delivered one at a time, in manifest order. */
    /**
     * Where {@link #streamLayers} delivers the image, blob by blob, as it lands.
     */
    public interface LayerSink {
        /** Called once, before the first layer. */
        void onImageConfig(Path configBlob) throws IOException, InterruptedException;

        void onLayer(Descriptor layer, Path blobPath) throws IOException, InterruptedException;
    }

    /** Called on the downloading thread once a blob is in the layout. */
    @FunctionalInterface
    private interface BlobArrival {
        void accept(String digestHex, Path blobPath);
    }

    /**
     * Virtual threads do not inherit SLF4J MDC; copy the caller map so dispatcher
     * milestones keep trace_id.
     */
    private static Void runPullWithInheritedMdc(Map<String, String> snapshot, PullTask task) throws Exception {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (snapshot != null) {
            MDC.setContextMap(snapshot);
        } else {
            MDC.clear();
        }
        try {
            task.run();
            return null;
        } finally {
            if (previous != null) {
                MDC.setContextMap(previous);
            } else {
                MDC.clear();
            }
        }
    }

    @FunctionalInterface
    private interface PullTask {
        void run() throws IOException;
    }

    private final class PullTaskPlanner {
        private final List<Callable<Void>> pullTasks;
        private final Set<String> scheduledDigests;
        private final Map<String, String> mdcSnapshot;
        private final String repository;
        private final Path blobsDir;
        private final BlobArrival onArrival;

        private PullTaskPlanner(int expectedTasks, Map<String, String> mdcSnapshot, String repository, Path blobsDir,
                BlobArrival onArrival) {
            this.pullTasks = new ArrayList<>(expectedTasks);
            this.scheduledDigests = new HashSet<>(expectedTasks);
            this.mdcSnapshot = mdcSnapshot;
            this.repository = repository;
            this.blobsDir = blobsDir;
            this.onArrival = onArrival;
        }

        private void addIfDigestNew(String digest, long size, MediaType mediaType) {
            if (!scheduledDigests.add(digest)) {
                return;
            }
            pullTasks.add(() -> runPullWithInheritedMdc(mdcSnapshot, () -> {
                // One task per layer on its own virtual thread, so MDC scopes the
                // digest to exactly this layer and every line the fetch produces
                // - source.select and source.fetch included - can be joined to it.
                MdcContext.putLayerDigest(digest);
                try {
                    Path blobPath = pullLayer(repository, ImageDigest.parse(digest), size, mediaType, blobsDir);
                    onArrival.accept(ImageDigest.parse(digest).hex(), blobPath);
                } finally {
                    MdcContext.clearLayerDigest();
                }
            }));
        }

        private List<Callable<Void>> pullTasks() {
            return pullTasks;
        }
    }

    private Path pullLayer(String repository, ImageDigest digest, long size, MediaType mediaType, Path blobsDir)
            throws IOException {
        var fetched = dispatcher.fetchLayer(new RepositoryName(repository), digest, size, mediaType);
        File tmp = fetched.path().toFile();
        Path landed = blobsDir.resolve(fetched.digest().hex());
        fs.copy(tmp.toPath(), landed);
        return landed;
    }

    /**
     * OCI index manifest descriptor must use the same media type as the blob (e.g.
     * Docker schema2 from Hub).
     */
    private static String indexManifestMediaType(ManifestResult manifestResult, Manifest manifest) {
        String fromResult = manifestResult.mediaType();
        if (fromResult != null && !fromResult.isBlank()) {
            return fromResult;
        }
        String fromManifest = manifest.mediaType();
        if (fromManifest != null && !fromManifest.isBlank()) {
            return fromManifest;
        }
        return MediaTypes.OCI_IMAGE_MANIFEST;
    }

    private static void runTar(Path archive, Path ociDir) throws IOException, InterruptedException {
        // tar -c: create archive, -f: output file, -C: change dir before adding "."
        Process p = new ProcessBuilder("tar", "-cf", archive.toString(), "-C", ociDir.toString(), ".")
                .redirectErrorStream(true).start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("tar failed with exit " + code);
        }
    }

    private static String readResource(String path) throws OciArchiveException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (var in = loader.getResourceAsStream(path)) {
            if (in == null) {
                String msg = AppError.OciErrorKind.RESOURCE_NOT_FOUND.format(path);
                throw new OciArchiveException(new AppError.OciError(AppError.OciErrorKind.RESOURCE_NOT_FOUND, msg),
                        msg);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            String msg = AppError.OciErrorKind.RESOURCE_READ_FAILED.format(path);
            throw new OciArchiveException(new AppError.OciError(AppError.OciErrorKind.RESOURCE_READ_FAILED, msg), msg,
                    e);
        }
    }

    private static long durationMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    private static void completeArrival(Map<String, CompletableFuture<Path>> arrivals, String digestHex,
            Path blobPath) {
        CompletableFuture<Path> arrival = arrivals.get(digestHex);
        if (arrival != null) {
            arrival.complete(blobPath);
        }
    }

    /**
     * A pull that fails must fail the layer consumer too, or it waits forever for a
     * blob that is never going to arrive.
     */
    private static Callable<Void> failArrivalsOnError(Callable<Void> task,
            Map<String, CompletableFuture<Path>> arrivals) {
        return () -> {
            try {
                return task.call();
            } catch (IOException | InterruptedException | RuntimeException e) {
                arrivals.values().forEach(arrival -> arrival.completeExceptionally(e));
                throw e;
            }
        };
    }

    private static Path awaitArrival(CompletableFuture<Path> arrival) throws IOException, InterruptedException {
        try {
            return arrival.get();
        } catch (ExecutionException e) {
            throw unwrapPullFailure(e);
        }
    }

    private static void awaitPull(Future<Void> future) throws IOException, InterruptedException {
        try {
            future.get();
        } catch (ExecutionException e) {
            throw unwrapPullFailure(e);
        }
    }

    /**
     * Rethrows what the pull task actually threw; the returned {@link IOException}
     * is only the last resort for a cause with no better home.
     */
    private static IOException unwrapPullFailure(ExecutionException e) throws InterruptedException {
        Throwable cause = e.getCause();
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof InterruptedException ie) {
            throw ie;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        if (cause instanceof RuntimeException re) {
            throw re;
        }
        return new IOException(cause);
    }
}
