package riid.client.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.cache.TokenCache;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;
import riid.client.core.model.auth.AuthChallenge;
import riid.client.core.model.auth.AuthParser;
import riid.client.core.model.auth.TokenResponse;
import riid.client.core.model.manifest.RegistryApi;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpRequestBuilder;
import riid.client.http.HttpResult;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Handles ping + Bearer token fetching with caching.
 */
public final class AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);

    private final HttpExecutor http;
    private final ObjectMapper mapper;
    private final TokenCache cache;

    private final long defaultTokenTtlSeconds;

    public AuthService(HttpExecutor http, ObjectMapper mapper, TokenCache cache) {
        this(http, mapper, cache, riid.client.core.config.AuthConfig.DEFAULT_TTL_SECONDS);
    }

    @SuppressFBWarnings({"EI_EXPOSE_REP2"})
    public AuthService(HttpExecutor http, ObjectMapper mapper, TokenCache cache, long defaultTokenTtlSeconds) {
        this.http = Objects.requireNonNull(http);
        this.mapper = Objects.requireNonNull(mapper).copy();
        this.cache = Objects.requireNonNull(cache);
        this.defaultTokenTtlSeconds = defaultTokenTtlSeconds;
    }

    /**
     * Return Authorization header value ("Bearer ...") or empty if no auth needed.
     */
    public Optional<String> getAuthHeader(RegistryEndpoint endpoint, String repository, String scope) {
        String cacheKey = cacheKey(endpoint, scope);
        Optional<String> cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            return cached.map(t -> "Bearer " + t);
        }

        // Ping to get challenge
        URI pingUri = HttpRequestBuilder.buildUri(
                endpoint.scheme(),
                endpoint.host(),
                endpoint.port(),
                RegistryApi.V2_PING);
        HttpResult<Void> pingResp = http.head(pingUri, Map.of());
        if (pingResp.statusCode() == HttpStatus.OK_200) {
            return Optional.empty(); // no auth needed
        }
        if (pingResp.statusCode() != HttpStatus.UNAUTHORIZED_401) {
            throw new ClientException(
                    new ClientError.Auth(
                            ClientError.AuthKind.UNEXPECTED_PING_STATUS,
                            pingResp.statusCode(),
                            "Unexpected ping status"),
                    "Unexpected ping status: " + pingResp.statusCode()
            );
        }
        Optional<AuthChallenge> ch = extractChallenge(pingResp.headers());
        if (ch.isEmpty()) {
            throw new ClientException(
                    new ClientError.Auth(
                            ClientError.AuthKind.MISSING_CHALLENGE,
                            pingResp.statusCode(),
                            "Missing WWW-Authenticate"),
                    "Missing WWW-Authenticate challenge");
        }
        AuthChallenge c = ch.get();
        String token = fetchToken(c, endpoint.credentialsOpt().orElse(null), scope);
        var ttlOpt = ttlFrom(pingResp.headers());
        long ttl = ttlOpt.orElse(defaultTokenTtlSeconds);
        if (ttlOpt.isEmpty()) {
            LOGGER.warn("No token TTL in headers; using default {}s", defaultTokenTtlSeconds);
        }
        cache.put(cacheKey, token, ttl); // fallback from config
        return Optional.of("Bearer " + token);
    }

    private Optional<AuthChallenge> extractChallenge(HttpFields headers) {
        return headers.getValuesList("WWW-Authenticate").stream()
                .map(AuthParser::parse)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private String fetchToken(AuthChallenge challenge, Credentials creds, String scope) {
        try {
            StringBuilder url = new StringBuilder(challenge.realm());
            if (challenge.service() != null) {
                url.append("?service=")
                        .append(URLEncoder.encode(challenge.service(), StandardCharsets.UTF_8));
            }
            if (scope != null && !scope.isBlank()) {
                if (!url.toString().contains("?")) {
                    url.append("?");
                } else {
                    url.append("&");
                }
                url.append("scope=")
                        .append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
            }
            var headers = new HashMap<String, String>();
            if (creds != null) {
                creds.identityToken().ifPresent(id -> headers.put("Authorization", "Bearer " + id));
                if (headers.isEmpty()) {
                    String basic = creds.username().orElse("") + ":" + creds.password().orElse("");
                    String enc = java.util.Base64.getEncoder()
                            .encodeToString(basic.getBytes(StandardCharsets.UTF_8));
                    headers.put("Authorization", "Basic " + enc);
                }
            }
            HttpResult<java.io.InputStream> resp = http.get(URI.create(url.toString()), headers);
            if (resp.statusCode() != HttpStatus.OK_200) {
                throw new ClientException(
                        new ClientError.Auth(
                                ClientError.AuthKind.TOKEN_FAILED,
                                resp.statusCode(),
                                "Token endpoint failed"),
                        "Token endpoint status: " + resp.statusCode()
                );
            }
            TokenResponse tr = mapper.readValue(resp.body(), TokenResponse.class);
            String token = Optional.ofNullable(tr.effectiveToken())
                    .orElseThrow(() -> new ClientException(
                            new ClientError.Auth(
                                    ClientError.AuthKind.NO_TOKEN,
                                    resp.statusCode(),
                                    "No token in response"),
                            "No token in response"));
            long ttl = Optional.ofNullable(tr.expiresInSeconds()).orElse(defaultTokenTtlSeconds);
            if (tr.expiresInSeconds() == null) {
                LOGGER.warn("Token response missing expires_in; using default {}s", defaultTokenTtlSeconds);
            }
            cache.put(cacheKeyFromChallenge(challenge, scope, creds), token, ttl);
            return token;
        } catch (IOException e) {
            throw new ClientException(
                    new ClientError.Auth(ClientError.AuthKind.TOKEN_FAILED, null, "Token endpoint IO error"),
                    "Token endpoint IO error",
                    e
            );
        }
    }

    private String cacheKey(RegistryEndpoint endpoint, String scope) {
        return endpoint.host() + "|" + scope + "|" + endpoint.credentialsOpt().map(Object::hashCode).orElse(0);
    }

    private String cacheKeyFromChallenge(AuthChallenge ch, String scope, Credentials creds) {
        return (ch.realm() + "|" + ch.service() + "|" + scope + "|" + (creds == null ? 0 : creds.hashCode()));
    }

    private Optional<Long> ttlFrom(HttpFields headers) {
        String v = headers.get("Docker-Token-Expires-In");
        if (v == null || v.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(v));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}

