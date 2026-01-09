package riid.client.unit;

import org.junit.jupiter.api.Test;
import riid.client.auth.AuthChallenge;
import riid.client.auth.AuthParser;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AuthParserTest {

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

