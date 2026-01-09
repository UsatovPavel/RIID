package riid.client.core.error;

/**
 * Domain error hierarchy for the registry client.
 */
public sealed interface ClientError permits ClientError.Auth, ClientError.Http, ClientError.Parse {
    record Auth(AuthKind kind, Integer status, String message) implements ClientError {}
    record Http(HttpKind kind, Integer status, String message) implements ClientError {}
    record Parse(ParseKind kind, String message) implements ClientError {}

    enum AuthKind { UNEXPECTED_PING_STATUS, MISSING_CHALLENGE, TOKEN_FAILED, NO_TOKEN }
    enum HttpKind { RETRY_EXHAUSTED, BAD_STATUS }
    enum ParseKind { MANIFEST, TOKEN, CONFIG }
}

