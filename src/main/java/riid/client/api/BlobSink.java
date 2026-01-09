package riid.client.api;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Abstraction for a blob download sink.
 */
public interface BlobSink {
    /**
     * Open an output stream to write blob bytes.
     */
    OutputStream open() throws IOException;

    /**
     * Optional locator for the stored blob (file path or opaque handle).
     */
    String locator();
}

