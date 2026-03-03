package riid.integration.p2p;

import java.io.IOException;
import java.util.EnumSet;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import DragonflyDfdaemon.v2.DfdaemonDownloadGrpc;
import DragonflyDfdaemon.v2.DownloadTaskRequest;
import DragonflyDfdaemon.v2.DownloadTaskResponse;
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
import riid.p2p.DfdaemonDownloader;

/**
 * Test implementation of {@link DfdaemonDownloader}: same as prod for v2 API.
 * v2 DfdaemonDownload.DownloadTask writes file directly to output path (no piece streaming).
 */
public final class StreamingDfdaemonDownloadClient implements DfdaemonDownloader {
    private static final int DEADLINE_SECONDS = 120;
    private static final EnumSet<Status.Code> RETRYABLE_CODES = EnumSet.of(
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.RESOURCE_EXHAUSTED);
    private static final int MAX_RETRIES = 2;

    private final ManagedChannel channel;
    private final DfdaemonDownloadGrpc.DfdaemonDownloadBlockingStub stub;

    public StreamingDfdaemonDownloadClient(String dfdaemonAddr) throws IOException {
        this.channel = buildChannel(dfdaemonAddr);
        this.stub = DfdaemonDownloadGrpc.newBlockingStub(channel);
    }

    @Override
    public Path download(DownloadTaskRequest request, Path outputPath) throws IOException {
        IOException lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                Iterator<DownloadTaskResponse> it = stub
                        .withDeadline(Deadline.after(DEADLINE_SECONDS, TimeUnit.SECONDS))
                        .downloadTask(request);
                while (it.hasNext()) {
                    DownloadTaskResponse r = it.next();
                    boolean finished = r.hasDownloadTaskStartedResponse()
                            && r.getDownloadTaskStartedResponse().getIsFinished();
                    if (finished) {
                        break;
                    }
                }
                if (!java.nio.file.Files.exists(outputPath) || java.nio.file.Files.size(outputPath) == 0) {
                    throw new IOException("dfdaemon did not produce output: " + outputPath);
                }
                return outputPath;
            } catch (StatusRuntimeException e) {
                lastException = new IOException("dfdaemon DownloadTask failed: " + e.getStatus(), e);
                if (isRetryable(e) && attempt < MAX_RETRIES - 1) {
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
        throw lastException != null ? lastException : new IOException("DownloadTask failed");
    }

    @Override
    public void close() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
            String target = "unix://" + path;
            try {
                return Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
                        .overrideAuthority("localhost")
                        .build();
            } catch (RuntimeException ignored) {
                // fallthrough to explicit netty setup
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
        throw new IllegalArgumentException("Invalid dfdaemonAddr (use unix:///path or host:port): " + addr);
    }

    private static boolean isRetryable(StatusRuntimeException e) {
        return RETRYABLE_CODES.contains(e.getStatus().getCode());
    }

}
