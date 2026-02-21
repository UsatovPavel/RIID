package riid.integration.p2p;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

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
import riid.p2p.DfdaemonDownloader;

/**
 * Test implementation of {@link DfdaemonDownloader}: same as prod for v2 API.
 * v2 DfdaemonDownload.DownloadTask writes file directly to output path (no piece streaming).
 */
public final class StreamingDfdaemonDownloadClient implements DfdaemonDownloader {
    private static final int DEADLINE_SECONDS = 120;

    private final ManagedChannel channel;
    private final DfdaemonDownloadGrpc.DfdaemonDownloadBlockingStub stub;

    public StreamingDfdaemonDownloadClient(String dfdaemonAddr) throws IOException {
        this.channel = buildChannel(dfdaemonAddr);
        this.stub = DfdaemonDownloadGrpc.newBlockingStub(channel);
    }

    @Override
    public Path download(DownloadTaskRequest request, Path outputPath) throws IOException {
        try {
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
        } catch (StatusRuntimeException e) {
            throw new IOException("dfdaemon DownloadTask failed: " + e.getStatus(), e);
        }
        if (!java.nio.file.Files.exists(outputPath) || java.nio.file.Files.size(outputPath) == 0) {
            throw new IOException("dfdaemon did not produce output: " + outputPath);
        }
        return outputPath;
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
