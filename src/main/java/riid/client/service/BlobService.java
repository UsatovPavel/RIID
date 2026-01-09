package riid.client.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.cache.CacheAdapter;
import riid.client.api.BlobRequest;
import riid.client.api.BlobResult;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;
import riid.client.core.model.manifest.RegistryApi;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpRequestBuilder;

import java.io.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Downloads blobs with optional Range and on-the-fly SHA256 validation.
 */
public class BlobService {
    private static final Logger log = LoggerFactory.getLogger(BlobService.class);

    private final HttpExecutor http;
    private final AuthService authService;
    private final CacheAdapter cacheAdapter;

    public BlobService(HttpExecutor http, AuthService authService) {
        this(http, authService, null);
    }

    public BlobService(HttpExecutor http, AuthService authService, CacheAdapter cacheAdapter) {
        this.http = Objects.requireNonNull(http);
        this.authService = Objects.requireNonNull(authService);
        this.cacheAdapter = cacheAdapter;
    }

    public BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, File target, String scope) {
        Objects.requireNonNull(target, "target file");

        URI uri = HttpRequestBuilder.buildUri(endpoint.scheme(), endpoint.host(), endpoint.port(), RegistryApi.blobPath(req.repository(), req.digest()));
        Map<String, String> headers = defaultHeaders();
        authService.getAuthHeader(endpoint, req.repository(), scope).ifPresent(v -> headers.put("Authorization", v));
        HttpResponse<InputStream> resp = http.get(uri, headers);
        if (resp.statusCode() != 200) {
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, resp.statusCode(), "Blob fetch failed"),
                    "Blob fetch failed: " + resp.statusCode());
        }

        long expectedSize = req.expectedSize() != null ? req.expectedSize() : resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (expectedSize <= 0) {
            log.warn("Missing Content-Length for blob {}", req.digest());
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Missing Content-Length for blob"),
                    "Missing Content-Length for blob download");
        }
        try (InputStream is = resp.body(); FileOutputStream fos = new FileOutputStream(target)) {
            String digest = writeAndHashStreaming(is, fos);
            validateDigest(digest, req.digest());
            validateSize(target, expectedSize);
            String mediaType = resp.headers().firstValue("Content-Type").orElse(req.mediaType());
            String path = target.getAbsolutePath();
            if (cacheAdapter != null) {
                var entry = cacheAdapter.put(
                        riid.cache.ImageDigest.parse(digest),
                        riid.cache.CachePayload.of(target.toPath(), target.length()),
                        riid.cache.CacheMediaType.from(mediaType));
                if (entry != null && entry.locator() != null && !entry.locator().isBlank()) {
                    path = entry.locator();
                }
            }
            return new BlobResult(digest, target.length(), mediaType, path);
        } catch (IOException e) {
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, resp.statusCode(), "Blob IO error"),
                    "Blob IO error",
                    e);
        }
    }

    public Optional<Long> headBlob(RegistryEndpoint endpoint, String repository, String digest, String scope) {
        URI uri = HttpRequestBuilder.buildUri(endpoint.scheme(), endpoint.host(), endpoint.port(), RegistryApi.blobPath(repository, digest));
        Map<String, String> headers = defaultHeaders();
        authService.getAuthHeader(endpoint, repository, scope).ifPresent(v -> headers.put("Authorization", v));
        HttpResponse<Void> resp = http.head(uri, headers);
        if (resp.statusCode() == 404) {
            return Optional.empty();
        }
        if (resp.statusCode() != 200) {
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, resp.statusCode(), "Blob HEAD failed"),
                    "Blob HEAD failed: " + resp.statusCode());
        }
        return resp.headers().firstValueAsLong("Content-Length").isPresent()
                ? Optional.of(resp.headers().firstValueAsLong("Content-Length").getAsLong())
                : Optional.empty();
    }

    private Map<String, String> defaultHeaders() {
        return new LinkedHashMap<>();
    }

    private void validateDigest(String computed, String expected) {
        if (expected != null && !expected.isBlank() && !expected.equals(computed)) {
            log.warn("Blob digest mismatch: expected {}, got {}", expected, computed);
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Blob digest mismatch"),
                    "Blob digest mismatch: expected %s, got %s".formatted(expected, computed));
        }
    }

    private void validateSize(File target, long expected) {
        if (expected > 0 && target.length() != expected) {
            log.warn("Blob size mismatch: expected {}, got {}", expected, target.length());
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Blob size mismatch"),
                    "Blob size mismatch: expected %d, got %d".formatted(expected, target.length()));
        }
    }

    private String writeAndHashStreaming(InputStream is, FileOutputStream fos) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        try (DigestInputStream dis = new DigestInputStream(is, md)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = dis.read(buf)) != -1) {
                fos.write(buf, 0, r);
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

