package riid.client.core.config;

import java.util.Objects;
import java.util.Optional;

/**
 * Registry credentials (basic or identity token).
 */
public final class Credentials {
    private final String username;
    private final String password;
    private final String identityToken;

    private Credentials(String username, String password, String identityToken) {
        this.username = username;
        this.password = password;
        this.identityToken = identityToken;
    }

    public static Credentials basic(String user, String pass) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(pass, "pass");
        return new Credentials(user, pass, null);
    }

    public static Credentials identityToken(String token) {
        Objects.requireNonNull(token, "token");
        return new Credentials(null, null, token);
    }

    public Optional<String> username() {
        return Optional.ofNullable(username);
    }

    public Optional<String> password() {
        return Optional.ofNullable(password);
    }

    public Optional<String> identityToken() {
        return Optional.ofNullable(identityToken);
    }
}

