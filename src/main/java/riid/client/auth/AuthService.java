package riid.client.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;
import riid.client.core.protocol.RegistryApi;
import riid.client.http.HttpExecutor;
import riid.client.http.HttpRequestBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Handles ping + Bearer token fetching with caching.
 */
public final class AuthService {
    private final HttpExecutor http;
    private final ObjectMapper mapper;
    private final TokenCache cache;

    public AuthService(HttpExecutor http, ObjectMapper mapper, TokenCache cache) {
        this.http = Objects.requireNonNull(http);
        this.mapper = Objects.requireNonNull(mapper);
        this.cache = Objects.requireNonNull(cache);
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
        URI pingUri = HttpRequestBuilder.buildUri(endpoint.scheme(), endpoint.host(), endpoint.port(), RegistryApi.V2_PING);
        HttpResponse<Void> pingResp = http.head(pingUri, Map.of());
        if (pingResp.statusCode() == 200) {
            return Optional.empty(); // no auth needed
        }
        if (pingResp.statusCode() != 401) {
            throw new ClientException(
                    new ClientError.Auth(ClientError.AuthKind.UNEXPECTED_PING_STATUS, pingResp.statusCode(), "Unexpected ping status"),
                    "Unexpected ping status: " + pingResp.statusCode()
            );
        }
        Optional<AuthChallenge> ch = extractChallenge(pingResp.headers());
        if (ch.isEmpty()) {
            throw new ClientException(
                    new ClientError.Auth(ClientError.AuthKind.MISSING_CHALLENGE, pingResp.statusCode(), "Missing WWW-Authenticate"),
                    "Missing WWW-Authenticate challenge"
            );
        }
        AuthChallenge c = ch.get();
        String token = fetchToken(c, endpoint.credentialsOpt().orElse(null), scope);
        cache.put(cacheKey, token, ttlFrom(pingResp.headers()).orElse(300L)); // fallback 5m
        return Optional.of("Bearer " + token);
    }

    private Optional<AuthChallenge> extractChallenge(HttpHeaders headers) {
        return headers.allValues("WWW-Authenticate").stream()
                .map(AuthParser::parse)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private String fetchToken(AuthChallenge challenge, Credentials creds, String scope) {
        try {
            StringBuilder url = new StringBuilder(challenge.realm());
            if (challenge.service() != null) {
                url.append("?service=").append(URLEncoder.encode(challenge.service(), StandardCharsets.UTF_8));
            }
            if (scope != null && !scope.isBlank()) {
                if (!url.toString().contains("?")) {
                    url.append("?");
                } else {
                    url.append("&");
                }
                url.append("scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
            }
            var headers = new HashMap<String, String>();
            if (creds != null) {
                creds.identityToken().ifPresent(id -> headers.put("Authorization", "Bearer " + id));
                if (headers.isEmpty()) {
                    String basic = creds.username().orElse("") + ":" + creds.password().orElse("");
                    String enc = java.util.Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8));
                    headers.put("Authorization", "Basic " + enc);
                }
            }
            HttpResponse<java.io.InputStream> resp = http.get(URI.create(url.toString()), headers);
            if (resp.statusCode() != 200) {
                throw new ClientException(
                        new ClientError.Auth(ClientError.AuthKind.TOKEN_FAILED, resp.statusCode(), "Token endpoint failed"),
                        "Token endpoint status: " + resp.statusCode()
                );
            }
            TokenResponse tr = mapper.readValue(resp.body(), TokenResponse.class);
            String token = Optional.ofNullable(tr.effectiveToken())
                    .orElseThrow(() -> new ClientException(
                            new ClientError.Auth(ClientError.AuthKind.NO_TOKEN, resp.statusCode(), "No token in response"),
                            "No token in response"));
            long ttl = Optional.ofNullable(tr.expiresInSeconds()).orElse(300L);
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

    private Optional<Long> ttlFrom(HttpHeaders headers) {
        return headers.firstValue("Docker-Token-Expires-In").map(Long::parseLong);
    }
}

