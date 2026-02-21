package riid.p2p;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import DragonflyDfdaemon.v2.Dfdaemon;
import DragonflyDfdaemon.v2.DfdaemonDownloadGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;

/**
 * gRPC client for dfdaemon DownloadTask. Sync/blocking.
 */
public final class DfdaemonDownloadClient implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DfdaemonDownloadClient.class);

    private final ManagedChannel channel;
    private final DfdaemonDownloadGrpc.DfdaemonDownloadBlockingStub stub;

    public DfdaemonDownloadClient(String dfdaemonAddr) throws IOException {
        this.channel = buildChannel(dfdaemonAddr);
        this.stub = DfdaemonDownloadGrpc.newBlockingStub(channel);
    }

    /**
     * Downloads via dfdaemon. Consumes stream until done. Returns output path.
     * dfdaemon does hardlink/copy to outputPath when need_piece_content=false.
     */
    public Path download(Dfdaemon.DownloadTaskRequest request, Path outputPath) throws IOException {
        try {
            Iterator<Dfdaemon.DownloadTaskResponse> it = stub.downloadTask(request);
            while (it.hasNext()) {
                Dfdaemon.DownloadTaskResponse r = it.next();
                if (r.hasDownloadTaskStartedResponse()) {
                    var started = r.getDownloadTaskStartedResponse();
                    if (started.getIsFinished()) {
                        break;
                    }
                }
            }
            if (!java.nio.file.Files.exists(outputPath)) {
                throw new IOException("dfdaemon did not create output file: " + outputPath);
            }
            return outputPath;
        } catch (StatusRuntimeException e) {
            throw new IOException("dfdaemon DownloadTask failed: " + e.getStatus(), e);
        }
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
                    .forAddress(new DomainSocketAddress(path))
                    .eventLoopGroup(new EpollEventLoopGroup())
                    .channelType(EpollDomainSocketChannel.class)
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
