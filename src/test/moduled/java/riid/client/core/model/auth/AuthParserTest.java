package riid.client.core.model.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthParserTest {

    @Test
    void parsesBearerWithParams() {
        String header = "Bearer realm=\"https://auth.example\", service=\"registry\", scope=\"repo:pull\"";

        Optional<AuthChallenge> result = AuthParser.parse(header);

        assertTrue(result.isPresent());
        AuthChallenge ch = result.get();
        assertEquals("https://auth.example", ch.realm());
        assertEquals("registry", ch.service());
        assertEquals("repo:pull", ch.scope());
    }

    @Test
    void picksFirstBearerAmongMultiple() {
        String header = "Basic realm=\"ignored\", Bearer realm=\"r1\", scope=\"s1\", Bearer realm=\"r2\"";

        Optional<AuthChallenge> result = AuthParser.parse(header);

        assertTrue(result.isPresent());
        AuthChallenge ch = result.get();
        assertEquals("r1", ch.realm());
        assertEquals("s1", ch.scope());
    }

    @Test
    void emptyOnNoBearer() {
        String header = "Basic realm=\"x\"";

        Optional<AuthChallenge> result = AuthParser.parse(header);

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyOnNullOrBlank() {
        assertTrue(AuthParser.parse(null).isEmpty());
        assertTrue(AuthParser.parse("   ").isEmpty());
    }

    @Test
    void emptyOnMalformedHeader() {
        String header = "Bearer realm=\"missing-quote";

        Optional<AuthChallenge> result = AuthParser.parse(header);

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyWhenRealmMissing() {
        String header = "Bearer service=\"registry\"";

        Optional<AuthChallenge> result = AuthParser.parse(header);

        assertTrue(result.isEmpty());
    }

    @Test
    void parsesBearerChallenge() {
        String header = "Bearer realm=\"https://auth.docker.io/token\",service=\"registry.docker.io\",scope=\"repository:library/alpine:pull\"";
        Optional<AuthChallenge> ch = AuthParser.parse(header);
        assertTrue(ch.isPresent());
        AuthChallenge c = ch.get();
        assertEquals("https://auth.docker.io/token", c.realm());
        assertEquals("registry.docker.io", c.service());
        assertEquals("repository:library/alpine:pull", c.scope());
    }

    @Test
    void emptyOnNonBearer() {
        assertTrue(AuthParser.parse("Basic foo").isEmpty());
    }
}

