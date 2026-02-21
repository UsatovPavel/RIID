package riid.p2p;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import DragonflyDfdaemon.v2.DfdaemonDownloadGrpc;
import DragonflyDfdaemon.v2.DownloadTaskRequest;
import DragonflyDfdaemon.v2.DownloadTaskResponse;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.net.UnixDomainSocketAddress;

import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDomainSocketChannel;

/**
 * gRPC client for dfdaemon DfdaemonDownload.DownloadTask (v2 API). Sync/blocking.
 * dfdaemon writes file directly to output path.
 */
public final class DfdaemonDownloadClient implements DfdaemonDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger(DfdaemonDownloadClient.class);

    private final ManagedChannel channel;
    private final DfdaemonDownloadGrpc.DfdaemonDownloadBlockingStub stub;

    public DfdaemonDownloadClient(String dfdaemonAddr) throws IOException {
        this.channel = buildChannel(dfdaemonAddr);
        this.stub = DfdaemonDownloadGrpc.newBlockingStub(channel);
    }

    private static final int DEADLINE_SECONDS = 120;
    private static final int MAX_RETRIES = 2;

    @Override
    public Path download(DownloadTaskRequest request, Path outputPath) throws IOException {
        IOException lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    LOGGER.debug("Retrying DownloadTask (attempt {})", attempt + 1);
                }
                Iterator<DownloadTaskResponse> it = stub
                        .withDeadline(Deadline.after(DEADLINE_SECONDS, TimeUnit.SECONDS))
                        .downloadTask(request);
                while (it.hasNext()) {
                    DownloadTaskResponse r = it.next();
                    if (r.hasDownloadTaskStartedResponse()
                            && r.getDownloadTaskStartedResponse().getIsFinished()) {
                        break;
                    }
                }
                if (!java.nio.file.Files.exists(outputPath)) {
                    throw new IOException("dfdaemon did not create output file: " + outputPath);
                }
                return outputPath;
            } catch (StatusRuntimeException e) {
                lastException = new IOException("dfdaemon DownloadTask failed: " + e.getStatus(), e);
                if (e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE && attempt < MAX_RETRIES - 1) {
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

    private static ManagedChannel buildChannel(String addr) throws IOException {
        if (addr == null || addr.isBlank()) {
            throw new IllegalArgumentException("dfdaemonAddr must not be blank");
        }
        String trimmed = addr.trim();
        if (trimmed.startsWith("unix://")) {
            String path = trimmed.substring(7).trim();
            if (path.isEmpty()) {
                throw new IllegalArgumentException("Invalid unix address: " + addr);
            }
            return NettyChannelBuilder
                    .forAddress(UnixDomainSocketAddress.of(path))
                    .eventLoopGroup(new NioEventLoopGroup())
                    .channelType(NioDomainSocketChannel.class)
                    .usePlaintext()
                    .build();
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
        throw new IllegalArgumentException("Invalid dfdaemonAddr (use unix:///path or host:port): " + addr);
    }
}
