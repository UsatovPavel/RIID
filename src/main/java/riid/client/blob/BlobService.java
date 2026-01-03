package riid.client.blob;

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
public final class BlobService {
    private final HttpExecutor http;
    private final AuthService authService;

    public BlobService(HttpExecutor http, AuthService authService) {
        this.http = Objects.requireNonNull(http);
        this.authService = Objects.requireNonNull(authService);
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
        try (InputStream is = resp.body(); FileOutputStream fos = new FileOutputStream(target)) {
            String digest = writeAndHashStreaming(is, fos);
            validateDigest(digest, req.digest());
            validateSize(target, expectedSize);
            String mediaType = resp.headers().firstValue("Content-Type").orElse(req.mediaType());
            return new BlobResult(digest, target.length(), mediaType, target.getAbsolutePath());
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

