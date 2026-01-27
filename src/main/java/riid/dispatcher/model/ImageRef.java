package riid.dispatcher.model;

/**
 * Image reference for dispatcher/registry layer: repository with tag or digest (no registry).
 * Usage: fetch/validate via RequestDispatcher.
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
 
