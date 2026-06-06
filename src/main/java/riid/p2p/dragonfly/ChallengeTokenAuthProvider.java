package riid.p2p.dragonfly;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.jetty.client.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import riid.cache.auth.TokenCache;
import riid.client.core.config.AuthConfig;
import riid.client.core.config.Credentials;
import riid.client.core.config.RegistryEndpoint;
import riid.client.http.HttpClientConfig;
import riid.client.http.HttpClientFactory;
import riid.client.http.HttpExecutor;
import riid.client.service.AuthService;
import ru.hse.dragonfly.puller.registry.RegistryAuth;

/**
 * Resolves auth via registry challenge flow (Bearer token with cache), then falls back to static credentials.
 */
public final class ChallengeTokenAuthProvider implements RegistryAuthProvider, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChallengeTokenAuthProvider.class);
    private static final String PULL_SCOPE_TEMPLATE = "repository:%s:pull";

    private final AuthService authService;
    private final AutoCloseable closeAction;

    public ChallengeTokenAuthProvider(HttpClientConfig httpConfig, AuthConfig authConfig) {
        HttpClientConfig effectiveHttpConfig = httpConfig != null ? httpConfig : new HttpClientConfig();
        AuthConfig effectiveAuthConfig = authConfig != null ? authConfig : new AuthConfig();
        HttpClient jettyClient = HttpClientFactory.create(effectiveHttpConfig, effectiveAuthConfig);
        HttpExecutor httpExecutor = new HttpExecutor(jettyClient, effectiveHttpConfig);
        this.authService = new AuthService(
                httpExecutor,
                new ObjectMapper(),
                new TokenCache(),
                effectiveAuthConfig.defaultTokenTtlSeconds());
        this.closeAction = jettyClient::stop;
    }

    ChallengeTokenAuthProvider(AuthService authService, AutoCloseable closeAction) {
        this.authService = Objects.requireNonNull(authService, "authService");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    @Override
    public RegistryAuth resolve(RegistryEndpoint endpoint, String repository) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(repository, "repository");
        Credentials credentials = endpoint.credentials();
        if (credentials == null) {
            return RegistryAuth.none();
        }
        String scope = PULL_SCOPE_TEMPLATE.formatted(repository);
        try {
            Optional<String> authHeader = authService.getAuthHeader(endpoint, repository, scope);
            if (authHeader.isPresent()) {
                return fromAuthorizationHeader(authHeader.get(), credentials);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Challenge auth resolution failed for {}: {}", endpoint.registryName(), e.getMessage());
        }
        return credentials.toRegistryAuth();
    }

    private static RegistryAuth fromAuthorizationHeader(String authorizationHeader, Credentials fallbackCredentials) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return fallbackCredentials.toRegistryAuth();
        }
        String header = authorizationHeader.trim();
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            if (!token.isEmpty()) {
                return RegistryAuth.bearer(token);
            }
            return fallbackCredentials.toRegistryAuth();
        }
        if (header.regionMatches(true, 0, "Basic ", 0, 6)) {
            String encoded = header.substring(6).trim();
            try {
                String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                int separator = decoded.indexOf(':');
                if (separator > 0) {
                    return RegistryAuth.basic(decoded.substring(0, separator), decoded.substring(separator + 1));
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to static credentials.
            }
        }
        return fallbackCredentials.toRegistryAuth();
    }

    @Override
    public void close() throws IOException {
        try {
            closeAction.close();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to close challenge auth provider", e);
        }
    }
}
