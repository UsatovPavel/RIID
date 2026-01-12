package riid.dispatcher;

/**
 * Image specification: repository with optional tag and digest.
 */
public record ImageRef(String repository, String tag, String digest) {
    public ImageRef {
        if (repository == null || repository.isBlank()) {
            throw new IllegalArgumentException("repository is blank");
        }
        if ((tag == null || tag.isBlank()) && (digest == null || digest.isBlank())) {
            throw new IllegalArgumentException("tag or digest must be provided");
        }
    }
}


