package riid.dispatcher.model;

import java.nio.file.Path;

import riid.cache.oci.ImageDigest;
import riid.core.model.manifest.MediaType;

/**
 * Result of image fetch orchestration.
 */
public record FetchResult(ImageDigest digest, MediaType mediaType, Path path) {
}
