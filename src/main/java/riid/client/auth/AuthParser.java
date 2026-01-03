package riid.client.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for WWW-Authenticate: Bearer ...
 */
public final class AuthParser {
    private static final Pattern BEARER = Pattern.compile("Bearer\\s+(.*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern KV = Pattern.compile("([a-zA-Z0-9_]+)=\"([^\"]*)\"");

    private AuthParser() {
    }

    public static Optional<AuthChallenge> parse(String header) {
        if (header == null) return Optional.empty();
        Matcher m = BEARER.matcher(header.trim());
        if (!m.find()) return Optional.empty();
        String rest = m.group(1);
        Matcher kv = KV.matcher(rest);
        String realm = null;
        String service = null;
        String scope = null;
        while (kv.find()) {
            String k = kv.group(1).toLowerCase(Locale.ROOT);
            String v = kv.group(2);
            switch (k) {
                case "realm" -> realm = v;
                case "service" -> service = v;
                case "scope" -> scope = v;
                default -> {}
            }
        }
        if (realm == null) return Optional.empty();
        return Optional.of(new AuthChallenge(realm, service, scope));
    }
}

