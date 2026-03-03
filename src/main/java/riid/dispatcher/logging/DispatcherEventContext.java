package riid.dispatcher.logging;

import java.util.Objects;

import riid.cache.oci.ImageDigest;
import riid.dispatcher.model.RepositoryName;

/**
 * Shared dispatcher event context for a single fetch flow.
 */
public record DispatcherEventContext(
        RepositoryName repository,
        ImageDigest digest,
        String mediaType
) {
    public DispatcherEventContext {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(mediaType, "mediaType");
    }
}
