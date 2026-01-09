package riid.runtime;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Placeholder Porto adapter.
 */
public class PortoRuntimeAdapter implements RuntimeAdapter {
    @Override
    public String runtimeId() {
        return "porto";
    }

    @Override
    public void importImage(Path imagePath) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Porto adapter not implemented");
    }
}


