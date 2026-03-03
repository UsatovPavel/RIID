package riid.client.logging.service;

import org.slf4j.Logger;

import riid.core.logging.StructuredLog;

/**
 * Structured event helpers for auth service.
 */
public final class AuthStructuredEvents {
    private static final String COMPONENT = "client";
    private static final String OPERATION = "auth.token";

    private AuthStructuredEvents() {
    }

    public static void tokenTtlHeaderMissing(Logger logger, long defaultTokenTtlSeconds) {
        StructuredLog.warn(
                logger,
                "auth.token.ttl",
                COMPONENT,
                OPERATION,
                "default_used",
                0L,
                AuthLogErrorCode.TOKEN_TTL_HEADER_MISSING.name(),
                "protocol",
                StructuredLog.fields("default_ttl_seconds", defaultTokenTtlSeconds)
        );
    }

    public static void tokenExpiresInMissing(Logger logger, long defaultTokenTtlSeconds) {
        StructuredLog.warn(
                logger,
                "auth.token.ttl",
                COMPONENT,
                OPERATION,
                "default_used",
                0L,
                AuthLogErrorCode.TOKEN_EXPIRES_IN_MISSING.name(),
                "protocol",
                StructuredLog.fields("default_ttl_seconds", defaultTokenTtlSeconds)
        );
    }

    /**
     * Stable auth-service error codes for structured logging.
     */
    private enum AuthLogErrorCode {
        TOKEN_TTL_HEADER_MISSING,
        TOKEN_EXPIRES_IN_MISSING
    }
}
