package riid.dispatcher;

import java.nio.file.Path;

import riid.cache.ImageDigest;
import riid.client.core.model.manifest.MediaType;

/**
 * Result of image fetch orchestration.
 */
public record FetchResult(ImageDigest digest, MediaType mediaType, Path path) {
}


