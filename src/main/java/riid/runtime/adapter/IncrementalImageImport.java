package riid.runtime.adapter;

import java.io.IOException;
import java.nio.file.Path;

import riid.core.model.manifest.Descriptor;

/**
 * One in-flight import of an image whose layers arrive one at a time, in
 * manifest order: layer {@code k} is handed over as soon as layers {@code 0..k}
 * are on disk, so the runtime works while the tail still downloads. Never
 * publishes a half-imported image.
 */
public interface IncrementalImageImport extends AutoCloseable {

    /**
     * Hands over the image config blob, once, before the first layer. An engine
     * that has to describe a partial image needs it; Porto ignores it.
     */
    default void imageConfig(Path configBlob) throws IOException, InterruptedException {
        // nothing to do for a runtime that imports layers without a config
    }

    /**
     * Imports one layer: {@code layer} as the manifest describes it,
     * {@code blobPath} its compressed blob on local disk.
     */
    void importLayer(Descriptor layer, Path blobPath) throws IOException, InterruptedException;

    /**
     * Publishes the image built from the layers imported so far; called exactly
     * once, after the last layer.
     */
    void finish() throws IOException, InterruptedException;

    @Override
    void close() throws IOException;
}
