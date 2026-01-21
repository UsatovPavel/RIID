package riid.client.api;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * File-based BlobSink.
 */
public final class FileBlobSink implements BlobSink {
    private final File targetFile;

    public FileBlobSink(File file) {
        this.targetFile = file;
    }

    @Override
    public OutputStream open() throws IOException {
        return new FileOutputStream(targetFile);
    }

    @Override
    public String locator() {
        return targetFile.getAbsolutePath();
    }

    public File file() {
        return targetFile;
    }

    @Override
    public void close() {
        // nothing to close; stream is owned by caller
    }
}

