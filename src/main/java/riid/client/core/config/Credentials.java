package riid.client.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.Optional;

/**
 * Registry credentials (basic or identity token).
 */
public final class Credentials {
    private final String usernameValue;
    private final String passwordValue;
    private final String identityTokenValue;

    @JsonCreator
    public Credentials(
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("identityToken") String identityToken) {
        this.usernameValue = username;
        this.passwordValue = password;
        this.identityTokenValue = identityToken;
    }

    public static Credentials basic(String user, String password) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
        return new Credentials(user, password, null);
    }

    public static Credentials identityToken(String token) {
        Objects.requireNonNull(token, "token");
        return new Credentials(null, null, token);
    }

    public Optional<String> username() {
        return Optional.ofNullable(usernameValue);
    }

    public Optional<String> password() {
        return Optional.ofNullable(passwordValue);
    }

    public Optional<String> identityToken() {
        return Optional.ofNullable(identityTokenValue);
    }
}
