package riid.p2p;

import java.io.IOException;

/**
 * Factory for creating {@link DfdaemonDownloader} instances.
 */
@FunctionalInterface
public interface DfdaemonDownloaderFactory {

    DfdaemonDownloader create(String dfdaemonAddr) throws IOException;
}
