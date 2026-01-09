package riid.dispatcher;

/**
 * Result of image fetch orchestration.
 */
public record FetchResult(String digest, String mediaType, String path) {
}


