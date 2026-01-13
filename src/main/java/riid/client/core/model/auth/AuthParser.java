package riid.client.core.model.auth;

import org.apache.hc.client5.http.auth.AuthChallenge;
import org.apache.hc.client5.http.auth.ChallengeType;
import org.apache.hc.client5.http.impl.auth.AuthChallengeParser;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.message.ParserCursor;
import org.apache.hc.core5.http.NameValuePair;

import java.util.List;
import java.util.Optional;

public final class AuthParser {
    private AuthParser() {}

    public static Optional<riid.client.core.model.auth.AuthChallenge> parse(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        AuthChallengeParser parser = AuthChallengeParser.INSTANCE;
        ParserCursor cursor = new ParserCursor(0, header.length());
        try {
            List<AuthChallenge> challenges = parser.parse(ChallengeType.TARGET, header, cursor);
            for (AuthChallenge ch : challenges) {
                if (!"bearer".equalsIgnoreCase(ch.getSchemeName())) {
                    continue;
                }
                String realm = ch.getValue();
                String service = null;
                String scope = null;
                List<NameValuePair> params = ch.getParams();
                if (params != null) {
                    for (NameValuePair p : params) {
                        String name = p.getName();
                        String val = p.getValue();
                        if (name == null) {
                            continue;
                        }
                        switch (name.toLowerCase(Locale.ROOT)) {
                            case "realm" -> realm = val;
                            case "service" -> service = val;
                            case "scope" -> scope = val;
                            default -> { }
                        }
                    }
                }
                if (realm != null) {
                    return Optional.of(new riid.client.core.model.auth.AuthChallenge(realm, service, scope));
                }
            }
            return Optional.empty();
        } catch (ParseException e) {
            return Optional.empty();
        }
    }
}