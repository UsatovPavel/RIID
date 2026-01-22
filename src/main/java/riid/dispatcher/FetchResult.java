package riid.dispatcher;

import riid.cache.oci.ImageDigest;
import riid.client.core.model.manifest.MediaType;

import java.nio.file.Path;

/**
 * Result of image fetch orchestration.
 */
public record FetchResult(ImageDigest digest, MediaType mediaType, Path path) {
}


