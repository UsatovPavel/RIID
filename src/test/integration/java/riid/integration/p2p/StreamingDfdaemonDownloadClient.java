package riid.integration.p2p;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import DragonflyCommon.v2.Common;
import DragonflyDfdaemon.v2.Dfdaemon;
import DragonflyDfdaemon.v2.DfdaemonDownloadGrpc;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.net.UnixDomainSocketAddress;

import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDomainSocketChannel;
import riid.p2p.DfdaemonDownloader;

/**
 * Test-only implementation of {@link DfdaemonDownloader}: uses need_piece_content=true,
 * writes file from stream. Avoids dfdaemon hardlink/copy which fails with cross-device on Docker.
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
    public Path download(Dfdaemon.DownloadTaskRequest request, Path outputPath) throws IOException {
        Dfdaemon.DownloadTaskRequest streamingRequest = buildStreamingRequest(request);
        Iterator<Dfdaemon.DownloadTaskResponse> it = stub
                .withDeadline(Deadline.after(DEADLINE_SECONDS, TimeUnit.SECONDS))
                .downloadTask(streamingRequest);
        try (SeekableByteChannel out = Files.newByteChannel(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            while (it.hasNext()) {
                Dfdaemon.DownloadTaskResponse r = it.next();
                if (r.hasDownloadTaskStartedResponse()) {
                    var started = r.getDownloadTaskStartedResponse();
                    if (started.getIsFinished()) {
                        break;
                    }
                } else if (r.hasDownloadPieceFinishedResponse()) {
                    var piece = r.getDownloadPieceFinishedResponse().getPiece();
                    if (piece.hasContent()) {
                        out.position(piece.getOffset());
                        out.write(ByteBuffer.wrap(piece.getContent().toByteArray()));
                    }
                }
            }
        } catch (StatusRuntimeException e) {
            throw new IOException("dfdaemon DownloadTask failed: " + e.getStatus(), e);
        }
        if (!Files.exists(outputPath) || Files.size(outputPath) == 0) {
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

    private static Dfdaemon.DownloadTaskRequest buildStreamingRequest(Dfdaemon.DownloadTaskRequest request) {
        Common.Download src = request.getDownload();
        Common.Download.Builder download = Common.Download.newBuilder()
                .setUrl(src.getUrl())
                .setNeedPieceContent(true)
                .setEnableTaskIdBasedBlobDigest(src.getEnableTaskIdBasedBlobDigest());
        if (src.hasDigest()) {
            download.setDigest(src.getDigest());
        }
        if (!src.getRequestHeaderMap().isEmpty()) {
            download.putAllRequestHeader(src.getRequestHeaderMap());
        }
        return Dfdaemon.DownloadTaskRequest.newBuilder()
                .setDownload(download.build())
                .build();
    }
}
