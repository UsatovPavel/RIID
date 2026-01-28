package riid.client.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import riid.cache.auth.TokenCache;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientException;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpResult;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class AuthServiceTest {

    TokenCache cache;
    ObjectMapper mapper;

    AuthService service;
    RegistryEndpoint endpoint;
    RecordingHttp http;

    @BeforeEach
    void setUp() {
        cache = new TokenCache();
        mapper = new ObjectMapper();
        http = new RecordingHttp();
        service = new AuthService(http, mapper, cache, 300);
        endpoint = new RegistryEndpoint("https", "registry.example", -1, Credentials.basic("u", "p"));
    }

    @Test
    void returnsEmptyWhenPingOk() {
        http.enqueueHead(new HttpResult<>(HttpStatus.OK_200, HttpFields.EMPTY, null, URI.create("https://x")));

        Optional<String> hdr = service.getAuthHeader(endpoint, "repo", "scope");

        assertTrue(hdr.isEmpty());
    }

    @Test
    void throwsOnUnexpectedPingStatus() {
        http.enqueueHead(new HttpResult<>(500, HttpFields.EMPTY, null, URI.create("https://x")));

        assertThrows(ClientException.class, () -> service.getAuthHeader(endpoint, "repo", "scope"));
    }

    @Test
    void throwsWhenChallengeMissing() {
        HttpFields.Mutable headers = HttpFields.build();
        http.enqueueHead(new HttpResult<>(HttpStatus.UNAUTHORIZED_401, headers, null, URI.create("https://x")));

        assertThrows(ClientException.class, () -> service.getAuthHeader(endpoint, "repo", "scope"));
    }

    @Test
    void fetchesTokenAndCaches() {
        HttpFields.Mutable pingHeaders = HttpFields.build();
        pingHeaders.add("WWW-Authenticate", "Bearer realm=\"https://auth\", service=\"svc\"");
        http.enqueueHead(new HttpResult<>(HttpStatus.UNAUTHORIZED_401, pingHeaders, null, URI.create("https://x")));

        byte[] body = "{\"token\":\"abc\",\"expires_in\":5}".getBytes(StandardCharsets.UTF_8);
        http.enqueueGet(new HttpResult<>(HttpStatus.OK_200, HttpFields.EMPTY,
                new ByteArrayInputStream(body), URI.create("https://auth")));

        Optional<String> hdr = service.getAuthHeader(endpoint, "repo", "repo:pull");

        assertTrue(hdr.isPresent());
        assertEquals("Bearer abc", hdr.get());

        Optional<String> cached = service.getAuthHeader(endpoint, "repo", "repo:pull");
        assertTrue(cached.isPresent());
        assertEquals("Bearer abc", cached.get());
        assertEquals(1, http.headCalls);
        assertEquals(1, http.getCalls);
    }

    private static final class RecordingHttp extends HttpExecutor {
        private final Deque<HttpResult<InputStream>> heads = new ArrayDeque<>();
        private final Deque<HttpResult<InputStream>> gets = new ArrayDeque<>();
        int headCalls = 0;
        int getCalls = 0;

        RecordingHttp() {
            super(new org.eclipse.jetty.client.HttpClient(), new HttpClientConfig());
        }

        void enqueueHead(HttpResult<InputStream> hr) {
            heads.add(hr);
        }

        void enqueueGet(HttpResult<InputStream> hr) {
            gets.add(hr);
        }

        @Override
        public HttpResult<InputStream> get(URI uri, Map<String, String> headers) {
            getCalls++;
            return gets.removeFirst();
        }

        @Override
        public HttpResult<Void> head(URI uri, Map<String, String> headers) {
            headCalls++;
            HttpResult<InputStream> hr = heads.removeFirst();
            return new HttpResult<>(hr.statusCode(), hr.headers(), null, hr.uri());
        }
    }
}

