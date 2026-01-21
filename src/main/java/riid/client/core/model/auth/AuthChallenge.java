package riid.client.core.model.auth;

import java.util.Objects;

/**
 * Parsed WWW-Authenticate challenge for Bearer.
 */
public record AuthChallenge(String realm, String service, String scope) {
    public AuthChallenge {
        Objects.requireNonNull(realm, "realm");
    }
}

