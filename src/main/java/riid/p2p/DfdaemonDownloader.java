package riid.p2p;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

import DragonflyDfdaemon.v2.DownloadTaskRequest;

/**
 * Abstraction for dfdaemon DfdaemonDownload.DownloadTask RPC client (v2 API).
 */
public interface DfdaemonDownloader extends Closeable {

    /**
     * Downloads via dfdaemon. Consumes stream until done. Returns output path.
     */
    Path download(DownloadTaskRequest request, Path outputPath) throws IOException;
}
