package riid.client.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.api.BlobSink;
import riid.client.api.FileBlobSink;
import riid.client.core.config.BlobPartialDownloadConfig;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;
import riid.core.model.manifest.RegistryApi;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpRequestBuilder;
import riid.client.http.HttpResult;
import riid.core.hash.Sha256Utils;

/**
 * Downloads blobs with optional Range and on-the-fly SHA256 validation.
 */
public class BlobService implements BlobServiceApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlobService.class);
    private static final String BLOB_IO_ERROR = "Blob IO error";
    private static final String BLOB_SINK_ERROR = "Blob sink error";

    private final HttpExecutor http;
    private final AuthService authService;
    private final BlobPartialDownloadConfig blobPartialDownloadConfig;

    public BlobService(HttpExecutor http, AuthService authService) {
        this(http, authService, null);
    }

    public BlobService(HttpExecutor http, AuthService authService,
            BlobPartialDownloadConfig blobPartialDownloadConfig) {
        this.http = Objects.requireNonNull(http);
        this.authService = Objects.requireNonNull(authService);
        this.blobPartialDownloadConfig = blobPartialDownloadConfig != null
                ? blobPartialDownloadConfig
                : new BlobPartialDownloadConfig();
    }

    @Override
    public BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, File target, String scope) {
        Objects.requireNonNull(target, "target file");
        try (BlobSink sink = new FileBlobSink(target)) {
            return fetchBlob(endpoint, req, sink, scope);
        } catch (IOException e) {
            LOGGER.warn("Blob IO error for {}/{}: {}", req.repository(), req.digest(), e.getMessage(), e);
            throw new ClientException(new ClientError.Parse(ClientError.ParseKind.MANIFEST, BLOB_IO_ERROR),
                    BLOB_IO_ERROR, e);
        } catch (Exception e) {
            LOGGER.warn("Blob sink error for {}/{}: {}", req.repository(), req.digest(), e.getMessage(), e);
            throw new ClientException(new ClientError.Parse(ClientError.ParseKind.MANIFEST, BLOB_SINK_ERROR),
                    BLOB_SINK_ERROR, e);
        }
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, BlobSink sink, String scope) {
        Objects.requireNonNull(sink, "sink");
        return fetchBlob(endpoint, req, sink, scope, true);
    }

    @SuppressWarnings("PMD.CloseResource")
    private BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, BlobSink sink, String scope,
            boolean allowRetryWithoutRange) {
        Objects.requireNonNull(sink, "sink");

        URI uri = endpoint.uri(RegistryApi.blobPath(req.repository(), req.digest()));
        Map<String, String> headers = defaultHeaders();
        authService.getAuthHeader(endpoint, req.repository(), scope).ifPresent(v -> headers.put("Authorization", v));
        boolean rangeEnabled = blobPartialDownloadConfig.mode() != BlobPartialDownloadConfig.RangeMode.OFF;
        String rangeValue = rangeEnabled ? req.rangeHeaderValue() : null;
        if (req.isRangeRequest() && !rangeEnabled) {
            LOGGER.warn("Range disabled by config for {}, ignoring requested range", req.digest());
        }
        if (rangeValue != null) {
            HttpRequestBuilder.withRange(headers, rangeValue);
        }
        HttpResult<InputStream> resp = http.get(uri, headers);
        int status = resp.statusCode();
        if (status == HttpStatus.RANGE_NOT_SATISFIABLE_416 && req.isRangeRequest() && rangeEnabled) {
            closeQuietly(resp.body());
            if (allowRetryWithoutRange && blobPartialDownloadConfig.retryWithoutRangeOnUnsatisfiableRange()) {
                LOGGER.warn("Range not satisfiable for {} (range={}), retrying without Range", req.digest(),
                        rangeValue);
                BlobRequest noRange = new BlobRequest(req.repository(), req.digest(), req.expectedSizeBytes(),
                        req.mediaType(), new BlobRequest.RangeSpec.All());
                return fetchBlob(endpoint, noRange, sink, scope, false);
            }
        }
        if (status < 200 || status >= 300) {
            String location = resp.firstHeader(HttpResult.HeaderName.LOCATION).orElse(null);
            String detail = location != null ? " (location=" + location + ")" : "";
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, status, "Blob fetch failed"),
                    "Blob fetch failed: " + status + detail);
        }

        ContentRange contentRange = null;
        if (status == HttpStatus.PARTIAL_CONTENT_206 && req.isRangeRequest() && rangeEnabled) {
            String raw = resp.firstHeader(HttpResult.HeaderName.CONTENT_RANGE).orElse(null);
            if (raw == null || raw.isBlank()) {
                throw new ClientException(new ClientError.Parse(ClientError.ParseKind.RANGE, "Missing Content-Range"),
                        "Missing Content-Range for partial blob download");
            }
            contentRange = ContentRange.parse(raw);
            contentRange.validateAgainst(req.range());
            var contentLength = resp.firstHeaderAsLong(HttpResult.HeaderName.CONTENT_LENGTH);
            if (contentLength.isPresent()) {
                long len = contentLength.getAsLong();
                if (len != contentRange.length()) {
                    throw new ClientException(
                            new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Content-Length mismatch"),
                            "Content-Length mismatch for partial blob download");
                }
            }
        } else if (req.isRangeRequest() && status == HttpStatus.OK_200) {
            LOGGER.warn("Range ignored by registry for {} (range={})", req.digest(), rangeValue);
        }

        OptionalLong expectedSizeOpt = resolveExpectedSizeBytes(req, resp, contentRange);
        if (expectedSizeOpt.isEmpty()) {
            LOGGER.warn("Missing Content-Length for blob {}", req.digest());
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Missing Content-Length for blob"),
                    "Missing Content-Length for blob download");
        }
        long expectedSize = expectedSizeOpt.getAsLong();
        Path sinkPath = null;
        if (sink instanceof FileBlobSink fbs) {
            sinkPath = fbs.file().toPath();
        }
        InputStream is = null;
        OutputStream os = null;
        try {
            is = resp.body();
            os = sink.open();
            boolean isFullRange = contentRange != null && contentRange.coversFull();
            if (contentRange != null && !isFullRange && blobPartialDownloadConfig
                    .partialDigestValidation() == BlobPartialDownloadConfig.PartialDigestValidation.REQUIRE_FULL) {
                throw new ClientException(
                        new ClientError.Parse(ClientError.ParseKind.RANGE, "Partial range requires full validation"),
                        "Partial range requires full validation");
            }
            boolean shouldValidateDigest = !req.isRangeRequest() || isFullRange;
            String digest;
            if (shouldValidateDigest) {
                digest = writeAndHashStreaming(is, os);
                validateDigest(digest, req.digest());
            } else {
                writeStreamingWithoutHash(is, os);
                digest = req.digest();
            }
            long actualSize = sinkPath != null ? sinkPath.toFile().length() : expectedSize;
            validateSize(actualSize, expectedSize);
            String mediaType = resp.firstHeader(HttpResult.HeaderName.CONTENT_TYPE).orElse(req.mediaType());
            return new BlobResult(digest, actualSize, mediaType, sink.locator());
        } catch (IOException e) {
            LOGGER.warn("Blob stream error for {}/{}: {}", req.repository(), req.digest(), e.getMessage(), e);
            throw new ClientException(new ClientError.Http(ClientError.HttpKind.BAD_STATUS, status, BLOB_IO_ERROR),
                    BLOB_IO_ERROR, e);
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
            throw new ClientException(new ClientError.Http(ClientError.HttpKind.BAD_STATUS, code, "Blob HEAD failed"),
                    "Blob HEAD failed: " + code);
        }
        return resp.firstHeaderAsLong(HttpResult.HeaderName.CONTENT_LENGTH).isPresent()
                ? Optional.of(resp.firstHeaderAsLong(HttpResult.HeaderName.CONTENT_LENGTH).getAsLong())
                : Optional.empty();
    }

    private Map<String, String> defaultHeaders() {
        return new LinkedHashMap<>();
    }

    private void validateDigest(String computed, String expected) {
        if (expected != null && !expected.isBlank() && !expected.equals(computed)) {
            LOGGER.warn("Blob digest mismatch: expected {}, got {}", expected, computed);
            throw new ClientException(new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Blob digest mismatch"),
                    "Blob digest mismatch: expected %s, got %s".formatted(expected, computed));
        }
    }

    private void validateSize(long actual, long expected) {
        if (expected > 0 && actual != expected) {
            LOGGER.warn("Blob size mismatch: expected {}, got {}", expected, actual);
            throw new ClientException(new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Blob size mismatch"),
                    "Blob size mismatch: expected %d, got %d".formatted(expected, actual));
        }
    }

    private static OptionalLong resolveExpectedSizeBytes(BlobRequest req, HttpResult<InputStream> resp,
            ContentRange contentRange) {
        if (contentRange != null) {
            return OptionalLong.of(contentRange.length());
        }
        Long expected = req.expectedSizeBytes();
        if (expected != null) {
            return OptionalLong.of(expected);
        }
        return resp.firstHeaderAsLong(HttpResult.HeaderName.CONTENT_LENGTH);
    }

    private static void writeStreamingWithoutHash(InputStream is, OutputStream os) throws IOException {
        is.transferTo(os);
    }

    private String writeAndHashStreaming(InputStream is, OutputStream os) throws IOException {
        try {
            return Sha256Utils.copyAndDigest(is, os);
        } catch (IllegalStateException e) {
            LOGGER.warn("Blob SHA-256 not available {}/{}: {}", is.toString(), os.toString(), e.getMessage(), e);
            throw new ClientException(new ClientError.Parse(ClientError.ParseKind.MANIFEST, "SHA-256 not available"),
                    "SHA-256 not available", e);
        }
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

}
