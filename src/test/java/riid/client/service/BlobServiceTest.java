package riid.client.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

@SuppressWarnings("PMD.CloseResource")
class BlobServiceTest {

    private static final String TEST_URI = "https://x";
    private static final String REPO = "library/busybox";
    private static final String MEDIA_TYPE = "application/octet-stream";
    private static final String SCOPE = "scope";
    private final RegistryEndpoint endpoint = RegistryEndpoint.https("registry-1.docker.io");

    @Test
    void closesSinkOnIoFailure() {
        FakeHttp http = new FakeHttp();
        http.nextGet = new HttpResult<>(HttpStatus.OK_200, HttpFields.EMPTY, 
            new FailingInputStream(), URI.create(TEST_URI));

        BlobService svc = new BlobService(http, new NoAuth(), null);
        RecordingSink sink = new RecordingSink();
        BlobRequest req = new BlobRequest(REPO, "sha256:ignored", 4L, MEDIA_TYPE);

        var ex = assertThrows(ClientException.class, () -> svc.fetchBlob(endpoint, req, sink, SCOPE));
        assertNotNull(ex.getMessage());
        assertEquals(RecordingSink.State.CLOSED, sink.state);
    }

    @Test
    void succeedsAndClosesResources() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        FakeHttp http = new FakeHttp();
        http.nextGet = new HttpResult<>(HttpStatus.OK_200, HttpFields.EMPTY,
                new ByteArrayInputStream(data), URI.create(TEST_URI));

        BlobService svc = new BlobService(http, new NoAuth(), null);
        RecordingSink sink = new RecordingSink();
        BlobRequest req = new BlobRequest(REPO, null, (long) data.length, MEDIA_TYPE);

        BlobResult result = svc.fetchBlob(endpoint, req, sink, SCOPE);

        assertEquals(RecordingSink.State.CLOSED, sink.state);
        assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.digest());
        assertEquals(data.length, result.size());
    }

    @Test
    void rangeHeaderIsSent() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        FakeHttp http = new FakeHttp();
        http.nextGet = new HttpResult<>(HttpStatus.OK_200, HttpFields.EMPTY,
                new ByteArrayInputStream(data), URI.create(TEST_URI));

        BlobService svc = new BlobService(http, new NoAuth(), null);
        RecordingSink sink = new RecordingSink();
        BlobRequest req = new BlobRequest(REPO, null, 3L, MEDIA_TYPE,
                new BlobRequest.RangeSpec(0L, 1L));

        svc.fetchBlob(endpoint, req, sink, SCOPE);
        assertEquals("bytes=0-1", http.lastHeaders.get("Range"));
    }

    @Test
    void partialContentSkipsDigestValidation() {
        byte[] data = "ab".getBytes(StandardCharsets.UTF_8);
        HttpFields.Mutable headers = HttpFields.build();
        headers.add("Content-Range", "bytes 0-1/10");
        headers.add("Content-Length", "2");

        FakeHttp http = new FakeHttp();
        http.nextGet = new HttpResult<>(HttpStatus.PARTIAL_CONTENT_206, headers,
                new ByteArrayInputStream(data), URI.create(TEST_URI));

        BlobService svc = new BlobService(http, new NoAuth(), null);
        RecordingSink sink = new RecordingSink();
        BlobRequest req = new BlobRequest(REPO, "sha256:full", 10L, MEDIA_TYPE,
                new BlobRequest.RangeSpec(0L, 1L));

        BlobResult result = svc.fetchBlob(endpoint, req, sink, SCOPE);

        assertEquals("sha256:full", result.digest());
        assertEquals(2L, result.size());
    }

    @Test
    void partialContentWithoutContentRangeFails() {
        byte[] data = "ab".getBytes(StandardCharsets.UTF_8);
        FakeHttp http = new FakeHttp();
        http.nextGet = new HttpResult<>(HttpStatus.PARTIAL_CONTENT_206, HttpFields.EMPTY,
                new ByteArrayInputStream(data), URI.create(TEST_URI));

        BlobService svc = new BlobService(http, new NoAuth(), null);
        RecordingSink sink = new RecordingSink();
        BlobRequest req = new BlobRequest(REPO, "sha256:full", 10L, MEDIA_TYPE,
                new BlobRequest.RangeSpec(0L, 1L));

        var ex = assertThrows(ClientException.class, () -> svc.fetchBlob(endpoint, req, sink, SCOPE));
        assertNotNull(ex.getMessage());
    }

    @Test
    void range416FallsBackToFullDownload() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        FakeHttp http = new FakeHttp();
        http.enqueue(new HttpResult<>(HttpStatus.RANGE_NOT_SATISFIABLE_416, HttpFields.EMPTY,
                new ByteArrayInputStream(new byte[0]), URI.create(TEST_URI)));
        http.enqueue(new HttpResult<>(HttpStatus.OK_200, HttpFields.EMPTY,
                new ByteArrayInputStream(data), URI.create(TEST_URI)));

        BlobService svc = new BlobService(http, new NoAuth(), null);
        RecordingSink sink = new RecordingSink();
        BlobRequest req = new BlobRequest(REPO, null, 3L, MEDIA_TYPE,
                new BlobRequest.RangeSpec(0L, 1L));

        BlobResult result = svc.fetchBlob(endpoint, req, sink, SCOPE);
        assertEquals(data.length, result.size());
        assertEquals(2, http.getCallCount());
    }

    private static final class FakeHttp extends HttpExecutor {
        HttpResult<InputStream> nextGet;
        private final ArrayDeque<HttpResult<InputStream>> responses = new ArrayDeque<>();
        private Map<String, String> lastHeaders = new LinkedHashMap<>();
        private int calls = 0;

        FakeHttp() {
            super(new org.eclipse.jetty.client.HttpClient(), new HttpClientConfig());
        }

        @Override
        public HttpResult<InputStream> get(URI uri, Map<String, String> headers) {
            calls++;
            lastHeaders = new LinkedHashMap<>(headers);
            if (!responses.isEmpty()) {
                return responses.removeFirst();
            }
            return nextGet;
        }

        @Override
        public HttpResult<Void> head(URI uri, Map<String, String> headers) {
            throw new UnsupportedOperationException("head not used");
        }

        void enqueue(HttpResult<InputStream> resp) {
            responses.add(resp);
        }

        int getCallCount() {
            return calls;
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

