package riid.client.api;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * File-based BlobSink.
 */
public final class FileBlobSink implements BlobSink {
    private final File file;

    public FileBlobSink(File file) {
        this.file = file;
    }

    @Override
    public OutputStream open() throws IOException {
        return new FileOutputStream(file);
    }

    @Override
    public String locator() {
        return file.getAbsolutePath();
    }

    public File file() {
        return file;
    }
}

