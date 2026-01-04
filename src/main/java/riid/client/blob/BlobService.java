package riid.client.blob;

import riid.cache.CacheAdapter;
import riid.client.auth.AuthService;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;
import riid.client.core.protocol.RegistryApi;
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
        return fetchBlob(endpoint, req, target, scope, false);
    }

    public BlobResult fetchBlob(RegistryEndpoint endpoint, BlobRequest req, File target, String scope, boolean resume) {
        Objects.requireNonNull(target, "target file");

        long expectedSizeHint = req.expectedSize() != null ? req.expectedSize() : -1;
        long existing = (resume && target.exists()) ? target.length() : 0L;
        if (resume && expectedSizeHint > 0 && existing >= expectedSizeHint) {
            // partial file larger/equal to expected size — restart
            if (target.exists() && !target.delete()) {
                throw new ClientException(
                        new ClientError.Http(ClientError.HttpKind.BAD_STATUS, -1, "Cannot truncate target"),
                        "Cannot truncate target file before resume");
            }
            existing = 0;
        }

        URI uri = HttpRequestBuilder.buildUri(endpoint.scheme(), endpoint.host(), endpoint.port(), RegistryApi.blobPath(req.repository(), req.digest()));
        Map<String, String> headers = defaultHeaders();
        authService.getAuthHeader(endpoint, req.repository(), scope).ifPresent(v -> headers.put("Authorization", v));
        if (existing > 0 && resume) {
            headers.put("Range", HttpExecutor.rangeHeader(existing, null));
        }

        HttpResponse<InputStream> resp = http.get(uri, headers);

        // If server ignored Range and we have partial file, restart from scratch
        if (resume && existing > 0 && resp.statusCode() == 200) {
            // truncate and retry without Range
            if (target.exists() && !target.delete()) {
                throw new ClientException(
                        new ClientError.Http(ClientError.HttpKind.BAD_STATUS, resp.statusCode(), "Cannot truncate target"),
                        "Cannot truncate target file for full download");
            }
            existing = 0;
            headers.remove("Range");
            resp = http.get(uri, headers);
        }

        if (resp.statusCode() != 200 && resp.statusCode() != 206) {
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, resp.statusCode(), "Blob fetch failed"),
                    "Blob fetch failed: " + resp.statusCode());
        }

        long expectedSize = req.expectedSize() != null ? req.expectedSize() : resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        boolean append = existing > 0;

        try (InputStream is = resp.body(); FileOutputStream fos = new FileOutputStream(target, append)) {
            writeStreaming(is, fos);
            // After download, validate full digest/size by re-reading file
            String digest = computeDigest(target);
            validateDigest(digest, req.digest());
            validateSize(target, expectedSize);
            String mediaType = resp.headers().firstValue("Content-Type").orElse(req.mediaType());
            String path = target.getAbsolutePath();
            if (cacheAdapter != null) {
                try (FileInputStream fin = new FileInputStream(target)) {
                    String cachedPath = cacheAdapter.put(digest, fin, target.length(), mediaType);
                    if (cachedPath != null && !cachedPath.isBlank()) {
                        path = cachedPath;
                    }
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
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Blob digest mismatch"),
                    "Blob digest mismatch: expected %s, got %s".formatted(expected, computed));
        }
    }

    private void validateSize(File target, long expected) {
        if (expected > 0 && target.length() != expected) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Blob size mismatch"),
                    "Blob size mismatch: expected %d, got %d".formatted(expected, target.length()));
        }
    }

    private void writeStreaming(InputStream is, FileOutputStream fos) throws IOException {
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) != -1) {
            fos.write(buf, 0, r);
        }
    }

    private String computeDigest(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            String hex = riid.client.core.util.Digests.sha256Hex(fis);
            return "sha256:" + hex;
        }
    }
}

