package riid.client.logging.service;

import org.slf4j.Logger;

import riid.core.logging.StructuredLog;

/**
 * Structured event helpers for manifest service.
 */
public final class ManifestStructuredEvents {
    private static final String COMPONENT = "client";
    private static final String OPERATION = "manifest.head";

    private ManifestStructuredEvents() {
    }

    public static void missingDockerContentDigest(Logger logger, String repository, String reference) {
        StructuredLog.warn(
                logger,
                "manifest.head",
                COMPONENT,
                OPERATION,
                "error",
                0L,
                ManifestLogErrorCode.MISSING_DOCKER_CONTENT_DIGEST.name(),
                "protocol",
                StructuredLog.fields(
                        "repository", repository,
                        "reference", reference
                )
        );
    }

    public static void missingContentLength(Logger logger, String repository, String reference) {
        StructuredLog.warn(
                logger,
                "manifest.head",
                COMPONENT,
                OPERATION,
                "error",
                0L,
                ManifestLogErrorCode.MISSING_CONTENT_LENGTH.name(),
                "protocol",
                StructuredLog.fields(
                        "repository", repository,
                        "reference", reference
                )
        );
    }

    /**
     * Stable manifest-service error codes for structured logging.
     */
    private enum ManifestLogErrorCode {
        MISSING_DOCKER_CONTENT_DIGEST,
        MISSING_CONTENT_LENGTH
    }
}
