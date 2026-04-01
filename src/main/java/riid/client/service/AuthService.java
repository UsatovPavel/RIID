package riid.client.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import riid.cache.auth.TokenCache;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.client.core.error.ClientError;
import riid.client.core.error.ClientException;
import riid.client.core.model.auth.AuthChallenge;
import riid.client.core.model.auth.AuthParser;
import riid.client.core.model.auth.TokenResponse;
import riid.core.model.manifest.RegistryApi;
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

import static java.util.Base64.getEncoder;

/**
 * Handles ping + Bearer token fetching with caching.
 *
 * <p>Optional stderr logging of secrets was disabled (see commented blocks in {@code fetchToken} / {@code getAuthHeader}).
 */
public class AuthService {
    public static final String DIRTY_REGISTRY_LOGS = "riid.dev.dirtyRegistryLogs";

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private static final String AUTH_PREFIX = "SECURITY:AUTH:";

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
            LOGGER.info(
                    "[registry auth] using cached bearer token for host={} scope={}",
                    endpoint.host(),
                    scope);
            // if (dirtyRegistryLogs()) {
            //     cached.ifPresent(t -> dirtyStderr("[registry auth][DIRTY] cached registry bearer token=" + t));
            // }
            return cached.map(t -> "Bearer " + t);
        }

        // Ping to get challenge
        URI pingUri = HttpRequestBuilder.buildUri(
                endpoint.scheme(),
                endpoint.host(),
                endpoint.port(),
                RegistryApi.V2_PING);
        LOGGER.info("[registry auth] HEAD {} (ping /v2/)", pingUri);
        HttpResult<Void> pingResp = http.head(pingUri, Map.of());
        LOGGER.info("[registry auth] ping status={}", pingResp.statusCode());
        if (pingResp.statusCode() == HttpStatus.OK_200) {
            LOGGER.info("[registry auth] registry allows anonymous pull for this host (no bearer)");
            return Optional.empty(); // no auth needed
        }
        if (pingResp.statusCode() != HttpStatus.UNAUTHORIZED_401) {
            String message = authMessage(
                    "UNEXPECTED_PING_STATUS",
                    "registry ping returned status " + pingResp.statusCode());
            throw new ClientException(
                    new ClientError.Auth(
                            ClientError.AuthKind.UNEXPECTED_PING_STATUS,
                            pingResp.statusCode(),
                            message),
                    message
            );
        }
        Optional<AuthChallenge> ch = extractChallenge(pingResp.headers());
        if (ch.isEmpty()) {
            String message = authMessage(
                    "MISSING_CHALLENGE",
                    "WWW-Authenticate challenge is missing");
            throw new ClientException(
                    new ClientError.Auth(
                            ClientError.AuthKind.MISSING_CHALLENGE,
                            pingResp.statusCode(),
                            message),
                    message);
        }
        AuthChallenge c = ch.get();
        LOGGER.info(
                "[registry auth] challenge realm={} service={} scope={}",
                c.realm(),
                c.service(),
                scope);
        String token = fetchToken(c, endpoint.credentialsOpt().orElse(null), scope);
        // if (dirtyRegistryLogs()) {
        //     dirtyStderr("[registry auth][DIRTY] registry bearer token from OAuth=" + token);
        // } else {
        LOGGER.info("[registry auth] bearer token obtained (set -D{}=true to log token)", DIRTY_REGISTRY_LOGS);
        // }
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
                creds.identityTokenOpt().ifPresent(id -> {
                    headers.put("Authorization", "Bearer " + id);
                    // if (dirtyRegistryLogs()) {
                    //     dirtyStderr("[registry auth][DIRTY] identity token for token endpoint=" + id);
                    // }
                });
                if (headers.isEmpty()) {
                    boolean hasUser = creds.usernameOpt().filter(s -> !s.isBlank()).isPresent();
                    boolean hasPass = creds.passwordOpt().filter(s -> !s.isBlank()).isPresent();
                    if (hasUser && hasPass) {
                        String user = creds.usernameOpt().orElse("");
                        String pass = creds.passwordOpt().orElse("");
                        // if (dirtyRegistryLogs()) {
                        //     dirtyStderr("[registry auth][DIRTY] user=" + user + " password/PAT=" + pass);
                        // }
                        String basic = user + ":" + pass;
                        String enc = getEncoder()
                                .encodeToString(basic.getBytes(StandardCharsets.UTF_8));
                        headers.put("Authorization", "Basic " + enc);
                        // if (dirtyRegistryLogs()) {
                        //     dirtyStderr("[registry auth][DIRTY] Authorization: Basic " + enc);
                        // }
                    }
                }
            }
            boolean authToTokenEndpoint = !headers.isEmpty();
            LOGGER.info(
                    "[registry auth] GET token endpoint (credentialsPresent={}) url={}",
                    authToTokenEndpoint,
                    url);
            HttpResult<java.io.InputStream> resp = http.get(URI.create(url.toString()), headers);
            LOGGER.info("[registry auth] token endpoint HTTP status={}", resp.statusCode());
            if (resp.statusCode() != HttpStatus.OK_200) {
                String message = authMessage(
                        "TOKEN_ENDPOINT_FAILED",
                        "token endpoint returned status " + resp.statusCode());
                throw new ClientException(
                        new ClientError.Auth(
                                ClientError.AuthKind.TOKEN_FAILED,
                                resp.statusCode(),
                                message),
                        message
                );
            }
            TokenResponse tr = mapper.readValue(resp.body(), TokenResponse.class);
            String token = Optional.ofNullable(tr.effectiveToken())
                    .orElseThrow(() -> new ClientException(
                            new ClientError.Auth(
                                    ClientError.AuthKind.NO_TOKEN,
                                    resp.statusCode(),
                                    authMessage("TOKEN_MISSING", "token is absent in auth response")),
                            authMessage("TOKEN_MISSING", "token is absent in auth response")));
            long ttl = Optional.ofNullable(tr.expiresInSeconds()).orElse(defaultTokenTtlSeconds);
            if (tr.expiresInSeconds() == null) {
                LOGGER.warn("Token response missing expires_in; using default {}s", defaultTokenTtlSeconds);
            }
            cache.put(cacheKeyFromChallenge(challenge, scope, creds), token, ttl);
            return token;
        } catch (IOException e) {
            String message = authMessage(
                    "TOKEN_ENDPOINT_IO_ERROR",
                    "I/O error while requesting auth token");
            throw new ClientException(
                            new ClientError.Auth(
                            ClientError.AuthKind.TOKEN_FAILED,
                            null,
                            message),
                    message,
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

    private static String authMessage(String kind, String details) {
        return AUTH_PREFIX + kind + ": " + details;
    }
}

