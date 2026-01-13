package riid.client.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.cache.oci.CacheAdapter;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.ImageDigest;
import riid.cache.oci.PathCachePayload;
import riid.cache.oci.ValidationException;
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
        BlobSink sink = new FileBlobSink(target);
        return fetchBlob(endpoint, req, sink, scope);
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
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
        InputStream is = null;
        java.io.OutputStream os = null;
        try {
            is = resp.body();
            os = sink.open();
            String digest = writeAndHashStreaming(is, os);
            validateDigest(digest, req.digest());
            long actualSize = sinkPath != null ? sinkPath.toFile().length() : expectedSize;
            validateSize(actualSize, expectedSize);
            String mediaType = resp.firstHeader("Content-Type").orElse(req.mediaType());
            String locator = sink.locator();
            if (cacheAdapter != null && sinkPath != null) {
                try {
                    var entry = cacheAdapter.put(
                            ImageDigest.parse(digest),
                            PathCachePayload.of(sinkPath, actualSize),
                            CacheMediaType.from(mediaType));
                    if (entry != null && entry.key() != null && !entry.key().isBlank()) {
                        locator = cacheAdapter.resolve(entry.key()).map(Path::toString).orElse(locator);
                    }
                } catch (ValidationException ve) {
                    throw new ClientException(
                            new ClientError.Parse(ClientError.ParseKind.MANIFEST, ve.getMessage()),
                            "Invalid blob media type: " + mediaType,
                            ve);
                } catch (IllegalArgumentException iae) {
                    throw new ClientException(
                            new ClientError.Parse(ClientError.ParseKind.MANIFEST, iae.getMessage()),
                            "Invalid blob media type: " + mediaType,
                            iae);
                }
            }
            return new BlobResult(digest, actualSize, mediaType, locator);
        } catch (IOException e) {
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, status, "Blob IO error"),
                    "Blob IO error",
                    e);
        } finally {
            try {
                sink.close();
            } catch (Exception closeEx) {
                LOGGER.warn("Failed to close sink: {}", closeEx.getMessage());
            }
            if (os != null) {
                try {
                    os.close();
                } catch (IOException ignore) {
                    LOGGER.warn("Failed to close sink stream: {}", ignore.getMessage());
                }
            }
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignore) {
                    LOGGER.warn("Failed to close response stream: {}", ignore.getMessage());
                }
            }
        }
    }

    public Optional<Long> headBlob(RegistryEndpoint endpoint, String repository, String digest, String scope) {
        URI uri = endpoint.uri(RegistryApi.blobPath(repository, digest));
        Map<String, String> headers = defaultHeaders();
        authService.getAuthHeader(endpoint, repository, scope).ifPresent(v -> headers.put("Authorization", v));
        HttpResult<Void> resp = http.head(uri, headers);
        int code = resp.statusCode();
        if (code == HttpStatus.NOT_FOUND_404) {
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
            dis.transferTo(os);
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

