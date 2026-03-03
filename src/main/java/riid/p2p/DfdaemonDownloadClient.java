package riid.p2p;

import java.io.IOException;
import java.time.Duration;
import java.util.EnumSet;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.Deadline;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.UnixDomainSocketAddress;

import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDomainSocketChannel;
import org.dragonflyoss.api.dfdaemon.v2.DfdaemonDownloadGrpc;
import org.dragonflyoss.api.dfdaemon.v2.DownloadTaskRequest;
import org.dragonflyoss.api.dfdaemon.v2.DownloadTaskResponse;

/**
 * gRPC client for dfdaemon DfdaemonDownload.DownloadTask (v2 API). Sync/blocking.
 * dfdaemon writes file directly to output path.
 */
public final class DfdaemonDownloadClient implements DfdaemonDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger(DfdaemonDownloadClient.class);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final int DEFAULT_MAX_ATTEMPTS = 2;

    private final ManagedChannel channel;
    private final DfdaemonDownloadGrpc.DfdaemonDownloadBlockingStub stub;
    private final long requestTimeoutMillis;
    private final int maxAttempts;

    public DfdaemonDownloadClient(String dfdaemonAddress) throws IOException {
        this(dfdaemonAddress, null, null);
    }

    public DfdaemonDownloadClient(String dfdaemonAddress, Duration requestTimeout, Integer maxRetries) throws IOException {
        this.channel = buildChannel(dfdaemonAddress);
        this.stub = DfdaemonDownloadGrpc.newBlockingStub(channel);
        this.requestTimeoutMillis = normalizeTimeoutMillis(requestTimeout);
        this.maxAttempts = normalizeMaxAttempts(maxRetries);
    }
    private static final EnumSet<Status.Code> RETRYABLE_CODES = EnumSet.of(
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.RESOURCE_EXHAUSTED);

    @Override
    public Path download(DownloadTaskRequest request, Path outputPath) throws IOException {
        String url = request.getDownload().getUrl();
        LOGGER.debug("DownloadTask start: url={}, output={}, timeoutMs={}, maxAttempts={}",
                url, outputPath, requestTimeoutMillis, maxAttempts);
        IOException lastException = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                if (attempt > 0) {
                    LOGGER.debug("Retrying DownloadTask (attempt {})", attempt + 1);
                }
                Iterator<DownloadTaskResponse> it = stub
                        .withDeadline(Deadline.after(requestTimeoutMillis, TimeUnit.MILLISECONDS))
                        .downloadTask(request);
                while (it.hasNext()) {
                    DownloadTaskResponse r = it.next();
                    boolean finished = r.hasDownloadTaskStartedResponse()
                            && r.getDownloadTaskStartedResponse().getIsFinished();
                    if (finished) {
                        break;
                    }
                }
                if (!java.nio.file.Files.exists(outputPath)) {
                    LOGGER.warn("DownloadTask completed but output file is missing: {}", outputPath);
                    throw new IOException("dfdaemon did not create output file: " + outputPath);
                }
                LOGGER.debug("DownloadTask success: output={}", outputPath);
                return outputPath;
            } catch (StatusRuntimeException e) {
                lastException = new IOException("dfdaemon DownloadTask failed: " + e.getStatus(), e);
                boolean retryable = isRetryable(e);
                boolean hasMoreAttempts = attempt < maxAttempts - 1;
                LOGGER.warn("DownloadTask attempt {}/{} failed with status {} (retryable={}, willRetry={})",
                        attempt + 1, maxAttempts, e.getStatus().getCode(), retryable, retryable && hasMoreAttempts);
                if (retryable && hasMoreAttempts) {
                    try {
                        Thread.sleep(500L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry", ie);
                    }
                } else {
                    throw lastException;
                }
            }
        }
        throw lastException != null ? lastException : new IOException("Download failed");
    }

    @Override
    public void close() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Channel shutdown interrupted");
        }
    }

    private static ManagedChannel buildChannel(String address) throws IOException {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("dfdaemonAddr must not be blank");
        }
        String trimmed = address.trim();
        if (trimmed.startsWith("unix://")) {
            String path = trimmed.substring(7).trim();
            if (path.isEmpty()) {
                throw new IllegalArgumentException("Invalid unix address: " + address);
            }
            String target = "unix://" + path;
            try {
                return Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
                        .overrideAuthority("localhost")
                        .build();
            } catch (RuntimeException ex) {
                LOGGER.debug("Auto UDS channel init failed for {}, fallback to explicit netty config", target, ex);
            }
            try {
                return NettyChannelBuilder
                        .forAddress(new DomainSocketAddress(path))
                        .eventLoopGroup(new NioEventLoopGroup())
                        .channelType(NioDomainSocketChannel.class)
                        .overrideAuthority("localhost")
                        .usePlaintext()
                        .build();
            } catch (RuntimeException ex) {
                LOGGER.debug("DomainSocketAddress path failed for {}, fallback to JDK UDS address", target, ex);
                return NettyChannelBuilder
                        .forAddress(UnixDomainSocketAddress.of(path))
                        .eventLoopGroup(new NioEventLoopGroup())
                        .channelType(NioDomainSocketChannel.class)
                        .overrideAuthority("localhost")
                        .usePlaintext()
                        .build();
            }
        }
        int colon = trimmed.lastIndexOf(':');
        if (colon > 0) {
            String host = trimmed.substring(0, colon).trim();
            int port = Integer.parseInt(trimmed.substring(colon + 1).trim());
            return NettyChannelBuilder
                    .forAddress(host, port)
                    .usePlaintext()
                    .build();
        }
        throw new IllegalArgumentException("Invalid dfdaemonAddr (use unix:///path or host:port): " + address);
    }

    private static boolean isRetryable(StatusRuntimeException e) {
        return RETRYABLE_CODES.contains(e.getStatus().getCode());
    }

    private static long normalizeTimeoutMillis(Duration requestTimeout) {
        Duration effective = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
        if (effective.isZero() || effective.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        return effective.toMillis();
    }

    private static int normalizeMaxAttempts(Integer maxRetries) {
        if (maxRetries == null) {
            return DEFAULT_MAX_ATTEMPTS;
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        return maxRetries + 1;
    }

}
