package riid.runtime.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.InputStreamRequestContent;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.core.fs.HostFilesystem;

/** Jetty HTTP/1.1 client for the Podman service exposed on a Unix socket. */
final class PodmanUnixSocketClient implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodmanUnixSocketClient.class);
    private static final String PODMAN_HOST_ENV = "CONTAINER_HOST";
    private static final String API_PREFIX = "/v4.0.0/libpod";
    private static final String ARCHIVE_MEDIA_TYPE = "application/x-tar";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_RESPONSE_HEADERS_BYTES = 16 * 1024;
    private static final ByteBufferPool.Sized REQUEST_BUFFER_POOL = new ByteBufferPool.Sized(null);

    static final String LOAD_PATH = API_PREFIX + "/images/load";

    private final Path socketPath;
    private final HostFilesystem fs;
    private final HttpClient httpClient;
    private boolean closed;

    static Optional<PodmanUnixSocketClient> fromEnvironment(HostFilesystem fs) {
        return fromContainerHost(System.getenv(PODMAN_HOST_ENV), fs);
    }

    static Optional<PodmanUnixSocketClient> fromContainerHost(String containerHost, HostFilesystem fs) {
        return unixSocketPath(containerHost).map(path -> new PodmanUnixSocketClient(path, fs));
    }

    PodmanUnixSocketClient(String containerHost, HostFilesystem fs) {
        this(unixSocketPath(containerHost).orElseThrow(
                () -> new IllegalArgumentException("CONTAINER_HOST must use unix://: " + containerHost)), fs);
    }

    private PodmanUnixSocketClient(Path socketPath, HostFilesystem fs) {
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
        this.fs = Objects.requireNonNull(fs, "fs");
        ClientConnector connector = new ClientConnector();
        this.httpClient = new HttpClient(new HttpClientTransportOverHTTP(connector));
        httpClient.setConnectTimeout(CONNECT_TIMEOUT.toMillis());
        httpClient.setIdleTimeout(REQUEST_TIMEOUT.toMillis());
        httpClient.setFollowRedirects(false);
        httpClient.setMaxResponseHeadersSize(MAX_RESPONSE_HEADERS_BYTES);
    }

    void loadArchive(Path archive) throws IOException, InterruptedException {
        long length = fs.size(archive);
        try (InputStream body = fs.newInputStream(archive)) {
            request("POST", LOAD_PATH, new ArchiveRequestContent(body, length));
        }
    }

    void loadArchive(InputStream archive) throws IOException, InterruptedException {
        request("POST", LOAD_PATH, new InputStreamRequestContent(ARCHIVE_MEDIA_TYPE, archive, REQUEST_BUFFER_POOL));
    }

    private void request(String method, String path, Request.Content content) throws IOException, InterruptedException {
        ensureStarted();
        Request request = httpClient.newRequest("localhost", 80).path(path).method(method)
                .transport(new Transport.TCPUnix(socketPath)).timeout(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .headers(headers -> headers.put(HttpHeader.CONNECTION, "close"));
        if (content != null) {
            request.body(content);
        }

        ContentResponse response;
        try {
            response = new CompletableResponseListener(request, MAX_RESPONSE_BYTES).send().get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            throw requestFailure(method, path, e.getCause());
        }
        String responseBody = new String(response.getContent(), StandardCharsets.UTF_8);
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IOException("Podman API " + method + " " + path + " failed with HTTP " + response.getStatus()
                    + ": " + responseBody);
        }
        LOGGER.debug("Podman API {} {} response: {}", method, path, responseBody);
    }

    private synchronized void ensureStarted() throws IOException {
        if (closed) {
            throw new IOException("Podman Unix socket client is closed");
        }
        if (httpClient.isStarted()) {
            return;
        }
        try {
            httpClient.start();
        } catch (Exception e) {
            throw new IOException("Failed to start Podman HTTP client", e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            httpClient.stop();
        } catch (Exception e) {
            throw new IOException("Failed to stop Podman HTTP client", e);
        }
    }

    private static IOException requestFailure(String method, String path, Throwable failure) {
        if (failure instanceof IOException io) {
            return io;
        }
        return new IOException("Podman API " + method + " " + path + " request failed", failure);
    }

    private static Optional<Path> unixSocketPath(String containerHost) {
        if (containerHost == null || containerHost.isBlank()) {
            return Optional.empty();
        }

        URI uri;
        try {
            uri = URI.create(containerHost);
        } catch (IllegalArgumentException e) {
            if (containerHost.regionMatches(true, 0, "unix:", 0, "unix:".length())) {
                throw new IllegalArgumentException("Invalid CONTAINER_HOST: " + containerHost, e);
            }
            LOGGER.warn("CONTAINER_HOST={} is not a Unix URI; using the Podman CLI fallback", containerHost);
            return Optional.empty();
        }
        if (!"unix".equalsIgnoreCase(uri.getScheme())) {
            LOGGER.warn("CONTAINER_HOST scheme '{}' is not supported by the direct client; using the Podman CLI "
                    + "fallback", uri.getScheme());
            return Optional.empty();
        }
        if (uri.getPath() == null || uri.getPath().isBlank() || uri.getRawAuthority() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("CONTAINER_HOST must be unix:///absolute/path: " + containerHost);
        }
        Path socketPath = Path.of(uri.getPath());
        if (!socketPath.isAbsolute()) {
            throw new IllegalArgumentException("CONTAINER_HOST socket path must be absolute: " + containerHost);
        }
        return Optional.of(socketPath);
    }

    private static final class ArchiveRequestContent extends InputStreamRequestContent {
        private final long length;

        private ArchiveRequestContent(InputStream body, long length) {
            super(ARCHIVE_MEDIA_TYPE, body, REQUEST_BUFFER_POOL);
            this.length = length;
        }

        @Override
        public long getLength() {
            return length;
        }
    }
}
