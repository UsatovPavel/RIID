package riid.client.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import riid.app.StatusCodes;
import riid.cache.CacheAdapter;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;
import riid.client.core.model.manifest.RegistryApi;
import riid.client.service.AuthService;
import riid.client.service.BlobService;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.TagList;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpRequestBuilder;
import riid.client.service.ManifestService;
import riid.cache.TokenCache;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Default RegistryClient implementation.
 */
public final class RegistryClientImpl implements RegistryClient {
    @SuppressWarnings("PMD.CloseResource")
    private static final String PULL_SCOPE_TEMPLATE = "repository:%s:pull";

    private final RegistryEndpoint endpoint;

    private final HttpExecutor http;

    private final AuthService authService;

    private final ManifestService manifestService;

    private final BlobService blobService;

    private final ObjectMapper mapper;

    @SuppressWarnings("PMD.CloseResource")
    public RegistryClientImpl(RegistryEndpoint endpoint,
                              HttpClientConfig httpConfig,
                              CacheAdapter cacheAdapter) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.mapper = new ObjectMapper();
        var client = HttpClientFactory.create(httpConfig);
        this.http = new HttpExecutor(client, httpConfig);
        this.authService = new AuthService(http, mapper, new TokenCache());
        this.manifestService = new ManifestService(http, authService, mapper);
        this.blobService = new BlobService(http, authService, cacheAdapter);
    }

    @Override
    public ManifestResult fetchManifest(String repository, String reference) {
        String scope = PULL_SCOPE_TEMPLATE.formatted(repository);
        return manifestService.fetchManifest(endpoint, repository, reference, scope);
    }

    @Override
    public BlobResult fetchConfig(String repository, Manifest manifest, File target) {
        String scope = PULL_SCOPE_TEMPLATE.formatted(repository);
        BlobRequest req = new BlobRequest(
                repository,
                manifest.config().digest(),
                manifest.config().size(),
                manifest.config().mediaType());
        return blobService.fetchBlob(endpoint, req, target, scope);
    }

    @Override
    public BlobResult fetchBlob(BlobRequest request, File target) {
        String scope = PULL_SCOPE_TEMPLATE.formatted(request.repository());
        return blobService.fetchBlob(endpoint, request, target, scope);
    }

    @Override
    public Optional<Long> headBlob(String repository, String digest) {
        String scope = PULL_SCOPE_TEMPLATE.formatted(repository);
        return blobService.headBlob(endpoint, repository, digest, scope);
    }

    @Override
    public TagList listTags(String repository, Integer n, String last) {
        String scope = "repository:%s:pull".formatted(repository);
        var headers = new HashMap<String, String>();
        authService.getAuthHeader(endpoint, repository, scope).ifPresent(v -> headers.put("Authorization", v));
        String path = RegistryApi.tagListPath(repository);
        StringBuilder query = new StringBuilder();
        if (n != null) {
            query.append("n=").append(n);
        }
        if (last != null && !last.isBlank()) {
            if (!query.isEmpty()) {
                query.append("&");
            }
            query.append("last=").append(last);
        }
        URI uri = HttpRequestBuilder.buildUri(
                endpoint.scheme(),
                endpoint.host(),
                endpoint.port(),
                path,
                query.isEmpty() ? null : query.toString());
        HttpResponse<java.io.InputStream> resp = http.get(uri, headers);
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            throw new ClientException(
                    new riid.client.core.error.ClientError.Http(
                            riid.client.core.error.ClientError.HttpKind.BAD_STATUS,
                            status,
                            "Tag list failed"),
                    "Tag list failed: " + status);
        }
        try (var body = resp.body()) {
            return mapper.readValue(body, TagList.class);
        } catch (IOException e) {
            throw new ClientException(
                    new riid.client.core.error.ClientError.Parse(
                            riid.client.core.error.ClientError.ParseKind.MANIFEST,
                            "Failed to parse tag list"),
                    "Failed to parse tag list",
                    e);
        }
    }
}

