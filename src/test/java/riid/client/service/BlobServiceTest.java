package riid.client.service;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.Test;
import riid.cache.auth.TokenCache;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.BlobSink;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientException;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlobServiceTest {

    private final RegistryEndpoint endpoint = RegistryEndpoint.https("registry-1.docker.io");

    @Test
    void closesSinkOnIoFailure() {
        FakeHttp http = new FakeHttp();
        http.nextGet = new HttpResult<>(HttpStatus.OK_200, HttpFields.EMPTY, new FailingInputStream(), URI.create("https://x"));

        BlobService svc = new BlobService(http, new NoAuth(), null);
        RecordingSink sink = new RecordingSink();
        BlobRequest req = new BlobRequest("library/busybox", "sha256:ignored", 4L, "application/octet-stream");

        assertThrows(ClientException.class, () -> svc.fetchBlob(endpoint, req, sink, "scope"));
        assertEquals(RecordingSink.State.CLOSED, sink.state);
    }

    @Test
    void succeedsAndClosesResources() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        FakeHttp http = new FakeHttp();
        http.nextGet = new HttpResult<>(HttpStatus.OK_200, HttpFields.EMPTY,
                new ByteArrayInputStream(data), URI.create("https://x"));

        BlobService svc = new BlobService(http, new NoAuth(), null);
        RecordingSink sink = new RecordingSink();
        BlobRequest req = new BlobRequest("library/busybox", null, (long) data.length, "application/octet-stream");

        BlobResult result = svc.fetchBlob(endpoint, req, sink, "scope");

        assertEquals(RecordingSink.State.CLOSED, sink.state);
        assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.digest());
        assertEquals(data.length, result.size());
    }

    private static final class FakeHttp extends HttpExecutor {
        HttpResult<InputStream> nextGet;

        FakeHttp() {
            super(new org.eclipse.jetty.client.HttpClient(), new HttpClientConfig());
        }

        @Override
        public HttpResult<InputStream> get(URI uri, Map<String, String> headers) {
            return nextGet;
        }

        @Override
        public HttpResult<Void> head(URI uri, Map<String, String> headers) {
            throw new UnsupportedOperationException("head not used");
        }
    }

    private static final class NoAuth extends AuthService {
        NoAuth() {
            super(new FakeHttp(), new com.fasterxml.jackson.databind.ObjectMapper(), new TokenCache(), 300);
        }

        @Override
        public Optional<String> getAuthHeader(RegistryEndpoint endpoint, String repository, String scope) {
            return Optional.empty();
        }
    }

    private static final class FailingInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("boom");
        }
    }

    private static final class RecordingSink implements BlobSink {
        enum State { OPENED, CLOSED }
        private final ByteArrayOutputStreamWithClose out = new ByteArrayOutputStreamWithClose();
        State state = null;

        @Override
        public OutputStream open() {
            state = State.OPENED;
            return out;
        }

        @Override
        public void close() {
            state = State.CLOSED;
        }

        @Override
        public String locator() {
            return "mem";
        }

        private static final class ByteArrayOutputStreamWithClose extends java.io.ByteArrayOutputStream {
            @Override
            public void close() throws IOException {
                super.close();
            }
        }
    }
}

