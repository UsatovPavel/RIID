package riid.dispatcher;

/**
 * Simple image reference: repo + tag/digest.
 */
public record ImageRef(String repository, String reference, boolean resumeAllowed) {
}


