package riid.client.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.app.StatusCodes;
import riid.cache.CacheAdapter;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.BlobSink;
import riid.client.api.FileBlobSink;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;
import riid.client.core.model.manifest.RegistryApi;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpResult;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Downloads blobs with optional Range and on-the-fly SHA256 validation.
 */
public class BlobService implements BlobServiceApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlobService.class);

    private final HttpExecutor http;
    private final AuthService authService;
    private final CacheAdapter cacheAdapter;

    public BlobService(HttpExecutor http, AuthService authService) {
        this(http, authService, null);
    }

    @SuppressFBWarnings({"EI_EXPOSE_REP2"})
    public BlobService(HttpExecutor http, AuthService authService, CacheAdapter cacheAdapter) {
        this.http = Objects.requireNonNull(http);
        this.authService = Objects.requireNonNull(authService);
        this.cacheAdapter = cacheAdapter;
    }

    @Override
    public BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, File target, String scope) {
        Objects.requireNonNull(target, "target file");
        return fetchBlob(endpoint, req, new FileBlobSink(target), scope);
    }

    @Override
    public BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, BlobSink sink, String scope) {
        Objects.requireNonNull(sink, "sink");

        URI uri = endpoint.uri(RegistryApi.blobPath(req.repository(), req.digest()));
        Map<String, String> headers = defaultHeaders();
        authService.getAuthHeader(endpoint, req.repository(), scope).ifPresent(v -> headers.put("Authorization", v));
        HttpResult<InputStream> resp = http.get(uri, headers);
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, status, "Blob fetch failed"),
                    "Blob fetch failed: " + status);
        }

        long expectedSize = req.expectedSizeBytes() != null
                ? req.expectedSizeBytes()
                : resp.firstHeaderAsLong("Content-Length").orElse(-1);
        if (expectedSize <= 0) {
            LOGGER.warn("Missing Content-Length for blob {}", req.digest());
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Missing Content-Length for blob"),
                    "Missing Content-Length for blob download");
        }
        Path sinkPath = null;
        if (sink instanceof FileBlobSink fbs) {
            sinkPath = fbs.file().toPath();
        }
        try (InputStream is = resp.body(); java.io.OutputStream os = sink.open()) {
            String digest = writeAndHashStreaming(is, os);
            validateDigest(digest, req.digest());
            long actualSize = sinkPath != null ? sinkPath.toFile().length() : expectedSize;
            validateSize(actualSize, expectedSize);
            String mediaType = resp.firstHeader("Content-Type").orElse(req.mediaType());
            String locator = sink.locator();
            if (cacheAdapter != null && sinkPath != null) {
                var entry = cacheAdapter.put(
                        riid.cache.ImageDigest.parse(digest),
                        riid.cache.CachePayload.of(sinkPath, actualSize),
                        riid.cache.CacheMediaType.from(mediaType));
                if (entry != null && entry.locator() != null && !entry.locator().isBlank()) {
                    locator = entry.locator();
                }
            }
            return new BlobResult(digest, actualSize, mediaType, locator);
        } catch (IOException e) {
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, status, "Blob IO error"),
                    "Blob IO error",
                    e);
        }
    }

    public Optional<Long> headBlob(RegistryEndpoint endpoint, String repository, String digest, String scope) {
        URI uri = endpoint.uri(RegistryApi.blobPath(repository, digest));
        Map<String, String> headers = defaultHeaders();
        authService.getAuthHeader(endpoint, repository, scope).ifPresent(v -> headers.put("Authorization", v));
        HttpResult<Void> resp = http.head(uri, headers);
        int code = resp.statusCode();
        if (code == StatusCodes.NOT_FOUND.code()) {
            return Optional.empty();
        }
        if (code < 200 || code >= 300) {
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, code, "Blob HEAD failed"),
                    "Blob HEAD failed: " + code);
        }
        return resp.firstHeaderAsLong("Content-Length").isPresent()
                ? Optional.of(resp.firstHeaderAsLong("Content-Length").getAsLong())
                : Optional.empty();
    }

    private Map<String, String> defaultHeaders() {
        return new LinkedHashMap<>();
    }

    private void validateDigest(String computed, String expected) {
        if (expected != null && !expected.isBlank() && !expected.equals(computed)) {
            LOGGER.warn("Blob digest mismatch: expected {}, got {}", expected, computed);
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Blob digest mismatch"),
                    "Blob digest mismatch: expected %s, got %s".formatted(expected, computed));
        }
    }

    private void validateSize(long actual, long expected) {
        if (expected > 0 && actual != expected) {
            LOGGER.warn("Blob size mismatch: expected {}, got {}", expected, actual);
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Blob size mismatch"),
                    "Blob size mismatch: expected %d, got %d".formatted(expected, actual));
        }
    }

    private String writeAndHashStreaming(InputStream is, java.io.OutputStream os) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "SHA-256 not available"),
                    "SHA-256 not available",
                    e);
        }
        try (DigestInputStream dis = new DigestInputStream(is, md)) {
            byte[] buf = new byte[8192];
            while (true) {
                int read = dis.read(buf);
                if (read == -1) {
                    break;
                }
                os.write(buf, 0, read);
            }
        }
        String hex = bytesToHex(md.digest());
        return "sha256:" + hex;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit((b) & 0xF, 16));
        }
        return sb.toString();
    }
}

