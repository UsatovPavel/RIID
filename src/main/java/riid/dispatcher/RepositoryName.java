package riid.dispatcher;

/**
 * Repository name wrapper to avoid passing arbitrary strings.
 */
public record RepositoryName(String value) {
    public RepositoryName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("repository is blank");
        }
    }
}


