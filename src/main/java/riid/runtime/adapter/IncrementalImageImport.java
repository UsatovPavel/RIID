package riid.runtime.adapter;

import java.io.IOException;
import java.nio.file.Path;

import riid.core.model.manifest.Descriptor;

/**
 * One in-flight import of an image whose layers arrive one at a time, before
 * the whole image is downloaded.
 *
 * <p>
 * Layers are fed in manifest order (bottom-first): the caller hands over layer
 * {@code k} as soon as layers {@code 0..k} are on disk, so the runtime does its
 * work while the tail of the image is still being fetched. {@link #finish()}
 * turns the imported layers into an addressable image and must be called
 * exactly once, after the last layer.
 *
 * <p>
 * {@link #close()} releases whatever the session holds; it never publishes a
 * half-imported image, so an aborted session leaves no image behind (already
 * imported layers may stay - they are content-addressed and reusable).
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
     * Imports a single layer blob.
     *
     * @param layer
     *            descriptor of this layer from the image manifest
     * @param blobPath
     *            compressed layer blob on local disk
     */
    void importLayer(Descriptor layer, Path blobPath) throws IOException, InterruptedException;

    /**
     * Publishes the image built from the layers imported so far.
     */
    void finish() throws IOException, InterruptedException;

    @Override
    void close() throws IOException;
}
