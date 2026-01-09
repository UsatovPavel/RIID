package riid.client.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.client.api.ManifestResult;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.ManifestIndex;
import riid.client.core.model.manifest.ManifestRef;
import riid.client.core.model.manifest.MediaTypes;
import riid.client.core.model.manifest.RegistryApi;
import riid.client.core.model.Digests;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpRequestBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Fetches and validates manifests.
 */
public final class ManifestService {
    private static final Logger log = LoggerFactory.getLogger(ManifestService.class);
    private static final List<String> ACCEPT = List.of(
            MediaTypes.OCI_IMAGE_MANIFEST,
            MediaTypes.DOCKER_MANIFEST_V2,
            MediaTypes.OCI_IMAGE_INDEX,
            MediaTypes.DOCKER_MANIFEST_LIST
    );

    private final HttpExecutor http;
    private final AuthService authService;
    private final ObjectMapper mapper;

    public ManifestService(HttpExecutor http, AuthService authService, ObjectMapper mapper) {
        this.http = Objects.requireNonNull(http);
        this.authService = Objects.requireNonNull(authService);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public ManifestResult fetchManifest(RegistryEndpoint endpoint, String repository, String reference, String scope) {
        URI uri = HttpRequestBuilder.buildUri(endpoint.scheme(), endpoint.host(), endpoint.port(), RegistryApi.manifestPath(repository, reference));
        Map<String, String> headers = defaultHeaders();
        authService.getAuthHeader(endpoint, repository, scope).ifPresent(v -> headers.put("Authorization", v));
        HttpResponse<java.io.InputStream> resp = http.get(uri, headers);
        if (resp.statusCode() != 200) {
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, resp.statusCode(), "Manifest fetch failed"),
                    "Manifest fetch failed: " + resp.statusCode());
        }
        try (var body = resp.body()) {
            byte[] bytes = body.readAllBytes();
            String contentType = resp.headers().firstValue("Content-Type").orElse(null);
            // Detect manifest list / index
            boolean isIndex = isIndexMediaType(contentType) || looksLikeIndex(bytes);
            if (isIndex) {
                ManifestIndex index = mapper.readValue(bytes, ManifestIndex.class);
                ManifestRef selected = selectEntry(index);
                if (selected == null) {
                    throw new ClientException(
                            new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Empty manifest list"),
                            "Empty manifest list");
                }
                // Recursively fetch the referenced manifest by digest
                return fetchManifest(endpoint, repository, selected.digest(), scope);
            }

            String computedDigest = "sha256:" + Digests.sha256Hex(bytes);
            Manifest manifest = mapper.readValue(bytes, Manifest.class);
            String mediaType = Optional.ofNullable(contentType).orElse(manifest.mediaType());
            long len = bytes.length;
            validateDigestHeader(resp.headers(), computedDigest);
            return new ManifestResult(computedDigest, mediaType, len, manifest);
        } catch (IOException e) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Failed to parse manifest"),
                    "Failed to parse manifest",
                    e);
        }
    }

    public Optional<ManifestResult> headManifest(RegistryEndpoint endpoint, String repository, String reference, String scope) {
        URI uri = HttpRequestBuilder.buildUri(endpoint.scheme(), endpoint.host(), endpoint.port(), RegistryApi.manifestPath(repository, reference));
        Map<String, String> headers = defaultHeaders();
        authService.getAuthHeader(endpoint, repository, scope).ifPresent(v -> headers.put("Authorization", v));
        HttpResponse<Void> resp = http.head(uri, headers);
        if (resp.statusCode() == 404) {
            return Optional.empty();
        }
        if (resp.statusCode() != 200) {
            throw new ClientException(
                    new ClientError.Http(ClientError.HttpKind.BAD_STATUS, resp.statusCode(), "Manifest HEAD failed"),
                    "Manifest HEAD failed: " + resp.statusCode());
        }
        String dcd = resp.headers().firstValue("Docker-Content-Digest").orElse(null);
        if (dcd == null || dcd.isBlank()) {
            log.warn("Manifest HEAD missing Docker-Content-Digest for {}/{}", repository, reference);
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Missing Docker-Content-Digest on manifest HEAD"),
                    "Missing Docker-Content-Digest on manifest HEAD");
        }
        String mediaType = resp.headers().firstValue("Content-Type").orElse(null);
        long len = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (len <= 0) {
            log.warn("Manifest HEAD missing Content-Length for {}/{}", repository, reference);
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Missing Content-Length on manifest HEAD"),
                    "Missing Content-Length on manifest HEAD");
        }
        return Optional.of(new ManifestResult(dcd, mediaType, len, null));
    }

    private Map<String, String> defaultHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", String.join(",", ACCEPT));
        return h;
    }

    private void validateDigestHeader(HttpHeaders headers, String computed) {
        Optional<String> header = headers.firstValue("Docker-Content-Digest");
        if (header.isPresent() && !header.get().equals(computed)) {
            throw new ClientException(
                    new ClientError.Parse(ClientError.ParseKind.MANIFEST, "Digest mismatch"),
                    "Manifest digest mismatch: header=%s computed=%s".formatted(header.get(), computed));
        }
    }

    private boolean isIndexMediaType(String mediaType) {
        if (mediaType == null) return false;
        return mediaType.contains(MediaTypes.OCI_IMAGE_INDEX) || mediaType.contains(MediaTypes.DOCKER_MANIFEST_LIST);
    }

    private boolean looksLikeIndex(byte[] bytes) {
        String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return s.contains("\"manifests\"");
    }

    private ManifestRef selectEntry(ManifestIndex index) {
        if (index.manifests() == null || index.manifests().isEmpty()) return null;
        // Prefer linux/amd64 if available, else first
        return index.manifests().stream()
                .filter(m -> m.platform() != null && "linux".equalsIgnoreCase(m.platform().os()) && "amd64".equalsIgnoreCase(m.platform().architecture()))
                .findFirst()
                .orElse(index.manifests().getFirst());
    }
}

