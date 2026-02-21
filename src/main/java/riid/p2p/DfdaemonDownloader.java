package riid.p2p;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

import DragonflyDfdaemon.v2.Dfdaemon;

/**
 * Abstraction for dfdaemon DownloadTask RPC client.
 * Implementations: {@link DfdaemonDownloadClient} (prod), test variant with need_piece_content stream.
 */
public interface DfdaemonDownloader extends Closeable {

    /**
     * Downloads via dfdaemon. Consumes stream until done. Returns output path.
     */
    Path download(Dfdaemon.DownloadTaskRequest request, Path outputPath) throws IOException;
}
