package riid.client.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token endpoint response.
 */
public record TokenResponse(
        @JsonProperty("token") String token,
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") Long expiresInSeconds,
        @JsonProperty("issued_at") String issuedAt
) {
    public String effectiveToken() {
        return token != null ? token : accessToken;
    }
}

