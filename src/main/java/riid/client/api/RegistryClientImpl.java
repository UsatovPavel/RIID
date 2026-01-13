package riid.client.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.client.HttpClient;
import riid.cache.auth.TokenCache;
import riid.cache.oci.CacheAdapter;
import riid.client.core.config.AuthConfig;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientException;
import riid.client.core.model.manifest.Manifest;
import riid.client.core.model.manifest.RegistryApi;
import riid.client.core.model.manifest.TagList;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpRequestBuilder;
import riid.client.http.HttpResult;
import riid.client.service.AuthService;
import riid.client.service.BlobService;
import riid.client.service.ManifestService;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default RegistryClient implementation through jetty. Throw exception on close.
 */
public final class RegistryClientImpl implements RegistryClient, AutoCloseable {
    private static final String PULL_SCOPE_TEMPLATE = "repository:%s:pull";

    private final RegistryEndpoint endpoint;
    private final HttpClient jettyClient;
    private final HttpExecutor http;
    private final AuthService authService;
    private final ManifestService manifestService;
    private final BlobService blobService;
    private final ObjectMapper mapper;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RegistryClientImpl(RegistryEndpoint endpoint,
                              HttpClientConfig httpConfig,
                              CacheAdapter cacheAdapter) {
        this(endpoint, httpConfig, cacheAdapter, AuthConfig.DEFAULT_TTL_SECONDS);
    }

    public RegistryClientImpl(RegistryEndpoint endpoint,
                              HttpClientConfig httpConfig,
                              CacheAdapter cacheAdapter,
                              long defaultTokenTtlSeconds) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.mapper = new ObjectMapper();
        this.jettyClient = HttpClientFactory.create(httpConfig);
        this.http = new HttpExecutor(jettyClient, httpConfig);
        this.authService = new AuthService(http, mapper, new TokenCache(), defaultTokenTtlSeconds);
        this.manifestService = new ManifestService(http, authService, mapper);
        this.blobService = new BlobService(http, authService, cacheAdapter);
    }

    @Override
    public ManifestResult fetchManifest(String repository, String reference) {
        String scope = pullScope(repository);
        return manifestService.fetchManifest(endpoint, repository, reference, scope);
    }

    @Override
    public BlobResult fetchConfig(String repository, Manifest manifest, File target) {
        String scope = pullScope(repository);
        BlobRequest req = new BlobRequest(
                repository,
                manifest.config().digest(),
                manifest.config().size(),
                manifest.config().mediaType());
        return blobService.fetchBlob(endpoint, req, target, scope);
    }

    @Override
    public BlobResult fetchBlob(BlobRequest request, File target) {
        String scope = pullScope(request.repository());
        return blobService.fetchBlob(endpoint, request, target, scope);
    }

    @Override
    public Optional<Long> headBlob(String repository, String digest) {
        String scope = pullScope(repository);
        return blobService.headBlob(endpoint, repository, digest, scope);
    }

    @Override
    public TagList listTags(String repository, Integer n, String last) {
        String scope = pullScope(repository);
        var headers = new HashMap<String, String>();
        authService.getAuthHeader(endpoint, repository, scope).ifPresent(v -> headers.put("Authorization", v));
        String path = RegistryApi.tagListPath(repository);
        String query = buildTagQuery(n, last);
        URI uri = HttpRequestBuilder.buildUri(
                endpoint.scheme(),
                endpoint.host(),
                endpoint.port(),
                path,
                query);
        HttpResult<java.io.InputStream> resp = http.get(uri, headers);
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

    @Override
    public void close() throws Exception {
        if (closed.compareAndSet(false, true)) {
            jettyClient.stop();
        }
    }

    private static String pullScope(String repository) {
        return PULL_SCOPE_TEMPLATE.formatted(repository);
    }

    private String buildTagQuery(Integer n, String last) {
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
        return query.isEmpty() ? null : query.toString();
    }
}

