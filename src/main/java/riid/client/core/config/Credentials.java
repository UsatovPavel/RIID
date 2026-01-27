package riid.client.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Optional;

/**
 * Registry credentials (basic or identity token).
 */
public record Credentials(
        @JsonProperty("username") String username,
        @JsonProperty("password") String password,
        @JsonProperty("identityToken") String identityToken
) {
    public Credentials {
        boolean hasBasic = username != null || password != null;
        boolean hasToken = identityToken != null;
        if (hasBasic && hasToken) {
            throw new IllegalArgumentException("Use either basic (username/password) or identityToken, not both");
        }
        if (hasBasic && (username == null || password == null)) {
            throw new IllegalArgumentException("Both username and password must be set for basic auth");
        }
    }

    public static Credentials basic(String user, String password) {
        return new Credentials(user, password, null);
    }

    public static Credentials identityToken(String token) {
        return new Credentials(null, null, token);
    }

    public Optional<String> usernameOpt() {
        return Optional.ofNullable(username);
    }

    public Optional<String> passwordOpt() {
        return Optional.ofNullable(password);
    }

    public Optional<String> identityTokenOpt() {
        return Optional.ofNullable(identityToken);
    }
}
