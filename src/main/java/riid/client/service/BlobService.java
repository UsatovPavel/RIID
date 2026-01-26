package riid.client.service;

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

import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import riid.cache.oci.CacheAdapter;
import riid.cache.oci.CacheMediaType;
import riid.cache.oci.FilesystemCachePayload;
import riid.cache.oci.ImageDigest;
import riid.cache.oci.ValidationException;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.BlobSink;
import riid.client.api.FileBlobSink;
import riid.client.core.config.RangeConfig;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;
import riid.client.core.model.manifest.RegistryApi;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpRequestBuilder;
import riid.client.http.HttpResult;

/**
 * Downloads blobs with optional Range and on-the-fly SHA256 validation.
 */
public class BlobService implements BlobServiceApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlobService.class);
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String RANGE_UNIT = "bytes";
    private static final String RANGE_WILDCARD = "*";
    private static final String INVALID_CONTENT_RANGE = "Invalid Content-Range";
    private static final String BLOB_IO_ERROR = "Blob IO error";
    private static final String BLOB_SINK_ERROR = "Blob sink error";
    private static final int EXPECTED_RANGE_PARTS = 2;

    private final HttpExecutor http;
    private final AuthService authService;
    private final CacheAdapter cacheAdapter;
    private final RangeConfig rangeConfig;

    public BlobService(HttpExecutor http, AuthService authService) {
        this(http, authService, null, null);
    }

    public BlobService(HttpExecutor http, AuthService authService, CacheAdapter cacheAdapter) {
        this(http, authService, cacheAdapter, null);
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "CacheAdapter lifecycle is managed by caller; BlobService does not mutate it")
    public BlobService(HttpExecutor http,
                       AuthService authService,
                       CacheAdapter cacheAdapter,
                       RangeConfig rangeConfig) {
        this.http = Objects.requireNonNull(http);
        this.authService = Objects.requireNonNull(authService);
        this.cacheAdapter = cacheAdapter;
        this.rangeConfig = rangeConfig != null ? rangeConfig : new RangeConfig();
    }

    @Override
    public BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, File target, String scope) {
        Objects.requireNonNull(target, "target file");
        try (BlobSink sink = new FileBlobSink(target)) {
            return fetchBlob(endpoint, req, sink, scope);
        } catch (IOException e) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, BLOB_IO_ERROR),
                    BLOB_IO_ERROR,
                    e);
        } catch (Exception e) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, BLOB_SINK_ERROR),
                    BLOB_SINK_ERROR,
                    e);
        }
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, BlobSink sink, String scope) {
        Objects.requireNonNull(sink, "sink");
        return fetchBlob(endpoint, req, sink, scope, true);
    }

    @SuppressWarnings("PMD.CloseResource")
    private BlobResult fetchBlob(RegistryEndpoint endpoint,
                                 BlobRequest req,
                                 BlobSink sink,
                                 String scope,
                                 boolean allowRetryWithoutRange) {
        Objects.requireNonNull(sink, "sink");

        URI uri = endpoint.uri(RegistryApi.blobPath(req.repository(), req.digest()));
        Map<String, String> headers = defaultHeaders();
        authService.getAuthHeader(endpoint, req.repository(), scope).ifPresent(v -> headers.put("Authorization", v));
        boolean rangeEnabled = rangeConfig.mode() != RangeConfig.RangeMode.OFF;
        String rangeValue = rangeEnabled ? req.rangeHeaderValue() : null;
        if (req.range() != null && !rangeEnabled) {
            LOGGER.warn("Range disabled by config for {}, ignoring requested range", req.digest());
        }
        if (rangeValue != null) {
            HttpRequestBuilder.withRange(headers, rangeValue);
        }
        HttpResult<InputStream> resp = http.get(uri, headers);
        int status = resp.statusCode();
        if (status == HttpStatus.RANGE_NOT_SATISFIABLE_416 && req.range() != null && rangeEnabled) {
            closeQuietly(resp.body());
            if (allowRetryWithoutRange && rangeConfig.fallbackToFullOn416()) {
                LOGGER.warn("Range not satisfiable for {} (range={}), retrying without Range",
                        req.digest(), rangeValue);
                BlobRequest noRange = new BlobRequest(
                        req.repository(),
                        req.digest(),
                        req.expectedSizeBytes(),
                        req.mediaType());
                return fetchBlob(endpoint, noRange, sink, scope, false);
            }
        }
        if (status < 200 || status >= 300) {
            String location = resp.firstHeader("Location").orElse(null);
            String detail = location != null
                    ? " (location=" + location + ")"
                    : "";
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, status, "Blob fetch failed"),
                    "Blob fetch failed: " + status + detail);
        }

        ContentRange contentRange = null;
        if (status == HttpStatus.PARTIAL_CONTENT_206 && req.range() != null && rangeEnabled) {
            String raw = resp.firstHeader("Content-Range").orElse(null);
            if (raw == null || raw.isBlank()) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Missing Content-Range"),
                        "Missing Content-Range for partial blob download");
            }
            contentRange = ContentRange.parse(raw);
            validateContentRange(contentRange, req.range());
            var contentLength = resp.firstHeaderAsLong(HEADER_CONTENT_LENGTH);
            if (contentLength.isPresent()) {
                long len = contentLength.getAsLong();
                if (len != contentRange.length()) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Content-Length mismatch"),
                        "Content-Length mismatch for partial blob download");
                }
            }
        } else if (req.range() != null && status == HttpStatus.OK_200) {
            LOGGER.warn("Range ignored by registry for {} (range={})", req.digest(), rangeValue);
        }

        long expectedSize = resolveExpectedSize(req, resp, contentRange);
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
            boolean isFullRange = contentRange != null && contentRange.coversFull();
            if (contentRange != null
                    && !isFullRange
                    && rangeConfig.partialValidation() == RangeConfig.PartialValidation.REQUIRE_FULL) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Partial range requires full validation"),
                        "Partial range requires full validation");
            }
            boolean shouldValidateDigest = req.range() == null || isFullRange;
            String digest;
            if (shouldValidateDigest) {
                digest = writeAndHashStreaming(is, os);
                validateDigest(digest, req.digest());
            } else {
                writeStreaming(is, os);
                digest = req.digest();
            }
            long actualSize = sinkPath != null ? sinkPath.toFile().length() : expectedSize;
            validateSize(actualSize, expectedSize);
            String mediaType = resp.firstHeader("Content-Type").orElse(req.mediaType());
            String locator = sink.locator();
            if (cacheAdapter != null && sinkPath != null && shouldValidateDigest) {
                try {
                    var entry = cacheAdapter.put(
                            ImageDigest.parse(digest),
                            FilesystemCachePayload.of(sinkPath, actualSize),
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
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, status, BLOB_IO_ERROR),
                    BLOB_IO_ERROR,
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

    private static long resolveExpectedSize(BlobRequest req,
                                            HttpResult<InputStream> resp,
                                            ContentRange contentRange) {
        if (contentRange != null) {
            return contentRange.length();
        }
        Long expected = req.expectedSizeBytes();
        if (expected != null) {
            return expected;
        }
        return resp.firstHeaderAsLong(HEADER_CONTENT_LENGTH).orElse(-1);
    }

    private static void validateContentRange(ContentRange range, BlobRequest.RangeSpec reqRange) {
        if (reqRange == null) {
            return;
        }
        if (reqRange.start() != null && !reqRange.start().equals(range.start())) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.RANGE, "Content-Range start mismatch"),
                    "Content-Range start mismatch");
        }
        if (reqRange.start() != null && reqRange.end() != null && !reqRange.end().equals(range.end())) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.RANGE, "Content-Range end mismatch"),
                    "Content-Range end mismatch");
        }
        Long totalSize = range.totalSize();
        if (reqRange.start() == null && reqRange.end() != null && totalSize != null) {
            long total = totalSize;
            long expectedStart = total - reqRange.end();
            long expectedEnd = total - 1;
            if (range.start() != expectedStart || range.end() != expectedEnd) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Content-Range suffix mismatch"),
                        "Content-Range suffix mismatch");
            }
        }
    }

    private static void writeStreaming(InputStream is, java.io.OutputStream os) throws IOException {
        is.transferTo(os);
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

    private static void closeQuietly(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private record ContentRange(long start, long end, Long totalSize) {
        long length() {
            return end - start + 1;
        }

        boolean coversFull() {
            return totalSize != null && start == 0 && end == totalSize - 1;
        }

        static ContentRange parse(String header) {
            String trimmed = header.trim();
            if (!trimmed.startsWith(RANGE_UNIT)) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Unsupported Content-Range"),
                        "Unsupported Content-Range: " + header);
            }
            String[] parts = trimmed.split(" ", 2);
            if (parts.length != EXPECTED_RANGE_PARTS) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, INVALID_CONTENT_RANGE),
                        INVALID_CONTENT_RANGE + ": " + header);
            }
            String[] rangeAndTotal = parts[1].split("/", 2);
            if (rangeAndTotal.length != EXPECTED_RANGE_PARTS) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, INVALID_CONTENT_RANGE),
                        INVALID_CONTENT_RANGE + ": " + header);
            }
            String[] range = rangeAndTotal[0].split("-", 2);
            if (range.length != EXPECTED_RANGE_PARTS) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, INVALID_CONTENT_RANGE),
                        INVALID_CONTENT_RANGE + ": " + header);
            }
            long start = parseLong(range[0], "Content-Range start");
            long end = parseLong(range[1], "Content-Range end");
            if (end < start) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, INVALID_CONTENT_RANGE),
                        "Content-Range end < start");
            }
            Long total = null;
            String totalRaw = rangeAndTotal[1].trim();
            if (!RANGE_WILDCARD.equals(totalRaw)) {
                total = parseLong(totalRaw, "Content-Range total");
                if (total <= 0) {
                    throw new ClientException(
                            new ClientError.Parse(ClientError.ParseKind.RANGE, "Invalid Content-Range total"),
                            "Content-Range total must be positive");
                }
            }
            return new ContentRange(start, end, total);
        }

        private static long parseLong(String raw, String label) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Invalid " + label),
                        "Invalid " + label + ": " + raw);
            }
        }
    }
}

