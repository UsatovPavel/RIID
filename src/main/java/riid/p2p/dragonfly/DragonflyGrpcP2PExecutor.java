package riid.p2p.dragonfly;

import riid.p2p.P2PExecutor;
import ru.hse.dragonfly.puller.DragonflyImagePuller;
import ru.hse.dragonfly.puller.blobpuller.PullResult;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;
import riid.client.core.config.RegistryEndpoint;
import riid.core.fs.PathSupport;

/**
 * P2P executor via gRPC to dfdaemon (v2 API). Fetch-only: returns path to
 * downloaded file. Dispatcher is responsible for cache.put().
 * <p>
 * Uses dfdaemonAddr from config (unix socket or tcp). For unix socket (e.g.
 * unix:///var/run/dragonfly/dfdaemon.sock), output goes to parent/output
 * (hostPath mount) so dfdaemon can write and RIID can read on host.
 */
public final class DragonflyGrpcP2PExecutor implements P2PExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DragonflyGrpcP2PExecutor.class);

    private final RegistryEndpoint endpoint;
    private final DragonflyConfig config;
    private final RegistryAuthProvider authProvider;
    private final PullerFactory pullerFactory;
    private volatile boolean closed;

    public DragonflyGrpcP2PExecutor(RegistryEndpoint endpoint, DragonflyConfig config) {
        this(endpoint, config, RegistryAuthProvider.passthrough(),
                (cfg, timeout) -> new ExternalDragonflyPuller(createPuller(cfg, timeout)));
    }

    public DragonflyGrpcP2PExecutor(RegistryEndpoint endpoint, DragonflyConfig config,
            RegistryAuthProvider authProvider) {
        this(endpoint, config, authProvider, (cfg, timeout) -> new ExternalDragonflyPuller(createPuller(cfg, timeout)));
    }

    public DragonflyGrpcP2PExecutor(RegistryEndpoint endpoint, DragonflyConfig config, PullerFactory pullerFactory) {
        this(endpoint, config, RegistryAuthProvider.passthrough(), pullerFactory);
    }

    public DragonflyGrpcP2PExecutor(RegistryEndpoint endpoint, DragonflyConfig config,
            RegistryAuthProvider authProvider, PullerFactory pullerFactory) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.config = Objects.requireNonNull(config, "config");
        this.authProvider = Objects.requireNonNull(authProvider, "authProvider");
        this.pullerFactory = Objects.requireNonNull(pullerFactory, "pullerFactory");
    }

    @Override
    public Optional<Path> fetch(String repository, ImageDigest digest, long size, CacheMediaType mediaType)
            throws IOException {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(digest, "digest");
        if (closed) {
            throw new IOException("dragonfly puller is already closed");
        }
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
        RegistryPullRequest request = RegistryPullRequestMapper.map(endpoint, repository, digest, outputPath,
                authProvider.resolve(endpoint, repository));
        Duration pullTimeout = config.requestTimeoutForSizeBytes(size);
        Puller puller = pullerFactory.create(config, pullTimeout);
        Path pulledPath = null;
        IOException pullFailure = null;
        try {
            PullResult result = puller.pull(request).join();
            pulledPath = result.path();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof DragonflyPullException dragonflyPullException) {
                pullFailure = new IOException("dragonfly pull failed: " + dragonflyPullException.getMessage(),
                        dragonflyPullException);
            } else {
                pullFailure = new IOException("dragonfly pull failed: " + cause.getMessage(), cause);
            }
        } catch (DragonflyPullException e) {
            pullFailure = new IOException("dragonfly pull failed: " + e.getMessage(), e);
        } finally {
            // A close() failure must never mask a pull that already succeeded - the
            // dispatcher would otherwise re-fetch from the registry a layer that is
            // already on disk. If the pull already failed, attach the close failure
            // instead of losing it.
            try {
                puller.close();
            } catch (Exception closeException) {
                // Deliberately NOT re-asserting the interrupt flag. The interruption
                // seen here comes from the puller shutting down its own dfdaemon
                // channel, not from anyone cancelling this task, and the caller's
                // next step after a successful fetch is a blocking file copy
                // (SimpleRequestDispatcher: cache.put). Setting the flag would make
                // that copy fail with ClosedByInterruptException and destroy the
                // very download this method exists to preserve.
                if (pullFailure != null) {
                    pullFailure.addSuppressed(closeException);
                } else {
                    LOGGER.warn("Failed to close dragonfly puller after a successful pull of {}", digest,
                            closeException);
                }
            }
        }
        if (pullFailure != null) {
            throw pullFailure;
        }
        return Optional.of(pulledPath);
    }

    /**
     * For unix socket (e.g. unix:///var/run/dragonfly/dfdaemon.sock), returns host
     * output dir (parent of socket + /output). dfdaemon writes there via hostPath
     * mount. For TCP proxy (e.g. 127.0.0.1:50051 via socat), use
     * DFDAEMON_OUTPUT_DIR if set.
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

    @Override
    public void close() {
        closed = true;
    }

    private static DragonflyImagePuller createPuller(DragonflyConfig cfg, Duration timeout) throws IOException {
        Integer retries = cfg.maxRetries();
        try {
            DragonflyImagePuller.Builder builder = DragonflyImagePuller.builder().withAddress(cfg.dfdaemonAddr());
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
        Puller create(DragonflyConfig config, Duration requestTimeout) throws IOException;
    }

    public interface Puller {
        CompletableFuture<PullResult> pull(RegistryPullRequest request) throws DragonflyPullException;

        default void close() throws Exception {
            // no-op for non-owning/test pullers
        }
    }

    private static final class ExternalDragonflyPuller implements Puller {
        private final DragonflyImagePuller delegate;

        private ExternalDragonflyPuller(DragonflyImagePuller delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<PullResult> pull(RegistryPullRequest request) throws DragonflyPullException {
            return delegate.pull(request);
        }

        @Override
        public void close() throws Exception {
            delegate.close();
        }
    }

}
