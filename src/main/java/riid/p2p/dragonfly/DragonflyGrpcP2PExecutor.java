package riid.p2p.dragonfly;

import riid.p2p.P2PExecutor;
import ru.hse.dragonfly.puller.DragonflyImagePuller;
import ru.hse.dragonfly.puller.blobpuller.PullResult;
import ru.hse.dragonfly.puller.error.DragonflyPullErrorKind;
import ru.hse.dragonfly.puller.error.DragonflyPullException;
import ru.hse.dragonfly.puller.registry.RegistryPullRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;
import riid.client.core.config.RegistryEndpoint;
import riid.core.fs.HostFilesystem;
import riid.core.fs.PathSupport;

/**
 * P2P executor via gRPC to dfdaemon (v2 API). Fetch-only: returns path to downloaded file.
 * Dispatcher is responsible for cache.put().
 * <p>
 * Uses dfdaemonAddr from config (unix socket or tcp).
 * For unix socket (e.g. unix:///var/run/dragonfly/dfdaemon.sock), output goes to parent/output
 * (hostPath mount) so dfdaemon can write and RIID can read on host.
 */
public final class DragonflyGrpcP2PExecutor implements P2PExecutor {
    private final RegistryEndpoint endpoint;
    private final DragonflyConfig config;
    private final PullerFactory pullerFactory;
    private volatile Puller sharedPuller;

    public DragonflyGrpcP2PExecutor(RegistryEndpoint endpoint,
                                    HostFilesystem fs,
                                    DragonflyConfig config) {
        this(endpoint, fs, config,
                cfg -> new ExternalDragonflyPuller(createPuller(cfg)));
    }

    public DragonflyGrpcP2PExecutor(RegistryEndpoint endpoint,
                                    HostFilesystem fs,
                                    DragonflyConfig config,
                                    PullerFactory pullerFactory) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(fs, "fs");
        this.config = Objects.requireNonNull(config, "config");
        this.pullerFactory = Objects.requireNonNull(pullerFactory, "pullerFactory");
    }

    @Override
    public Optional<Path> fetch(String repository, ImageDigest digest, long size, CacheMediaType mediaType)
            throws IOException {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(digest, "digest");
        if (!config.enabledOrDefault()) {
            return Optional.empty();
        }
        String dfdaemonAddr = config.dfdaemonAddr();
        Path outputPath;
        Path hostOutputDir = unixSocketHostOutputDir(dfdaemonAddr);
        if (hostOutputDir != null) {
            String filename = "p2p-" + UUID.randomUUID() + ".bin";
            outputPath = hostOutputDir.resolve(filename);
        } else {
            outputPath = PathSupport.temporaryPath("p2p-", ".bin");
        }
        RegistryPullRequest request = RegistryPullRequestMapper.map(endpoint, repository, digest, outputPath);
        Puller puller = getOrCreatePuller();
        try {
            PullResult result = puller.pull(request).join();
            return Optional.of(result.path());
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof DragonflyPullException dragonflyPullException) {
                throw new IOException("dragonfly pull failed: " + dragonflyPullException.getMessage(), dragonflyPullException);
            }
            throw new IOException("dragonfly pull failed: " + cause.getMessage(), cause);
        } catch (DragonflyPullException e) {
            throw new IOException("dragonfly pull failed: " + e.getMessage(), e);
        }
    }

    /**
     * For unix socket (e.g. unix:///var/run/dragonfly/dfdaemon.sock), returns host output dir
     * (parent of socket + /output). dfdaemon writes there via hostPath mount.
     * For TCP proxy (e.g. 127.0.0.1:50051 via socat), use DFDAEMON_OUTPUT_DIR if set.
     */
    private static Path unixSocketHostOutputDir(String addr) {
        if (addr != null && addr.trim().startsWith("unix://")) {
            String path = addr.trim().substring(7).trim();
            if (!path.isEmpty()) {
                Path p = Path.of(path);
                Path parent = p.getParent();
                if (parent != null) {
                    return parent.resolve("output");
                }
            }
        }
        String envDir = System.getenv("DFDAEMON_OUTPUT_DIR");
        return (envDir != null && !envDir.isBlank()) ? Path.of(envDir.trim()) : null;
    }

    @Override
    public void publish(ImageDigest digest, Path path, long size, CacheMediaType mediaType) {
        // Not supported
    }

    private Puller getOrCreatePuller() throws IOException {
        Puller current = sharedPuller;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (sharedPuller == null) {
                sharedPuller = pullerFactory.create(config);
            }
            return sharedPuller;
        }
    }

    private static DragonflyImagePuller createPuller(DragonflyConfig cfg) throws IOException {
        Duration timeout = cfg.requestTimeout();
        Integer retries = cfg.maxRetries();
        try {
            DragonflyImagePuller.Builder builder = DragonflyImagePuller.builder()
                    .withAddress(cfg.dfdaemonAddr());
            if (timeout != null) {
                builder = builder.withRequestTimeout(timeout);
            }
            if (retries != null) {
                builder = builder.withMaxRetries(retries);
            }
            return builder.build();
        } catch (DragonflyPullException e) {
            throw new IOException("failed to initialize DragonflyImagePuller: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    public interface PullerFactory {
        Puller create(DragonflyConfig config) throws IOException;
    }

    @FunctionalInterface
    public interface Puller {
        CompletableFuture<PullResult> pull(RegistryPullRequest request) throws DragonflyPullException;
    }

    private static final class ExternalDragonflyPuller implements Puller {
        private final DragonflyImagePuller delegate;

        private ExternalDragonflyPuller(DragonflyImagePuller delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<PullResult> pull(RegistryPullRequest request) throws DragonflyPullException {
            Object result = delegate.pull(request);
            if (result instanceof CompletableFuture<?> futureResult) {
                CompletableFuture<PullResult> converted = new CompletableFuture<>();
                futureResult.whenComplete((value, error) -> {
                    if (error != null) {
                        converted.completeExceptionally(error);
                        return;
                    }
                    if (value instanceof PullResult pullResult) {
                        converted.complete(pullResult);
                        return;
                    }
                    String valueType = value == null ? "null" : value.getClass().getName();
                    converted.completeExceptionally(new DragonflyPullException(
                            DragonflyPullErrorKind.INTERNAL,
                            "unexpected future pull result type: " + valueType
                    ));
                });
                return converted;
            }
            if (result instanceof PullResult pullResult) {
                return CompletableFuture.completedFuture(pullResult);
            }
            String resultType = result == null ? "null" : result.getClass().getName();
            throw new DragonflyPullException(
                    DragonflyPullErrorKind.INTERNAL,
                    "unexpected pull result type: " + resultType
            );
        }
    }
}
