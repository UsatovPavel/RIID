package riid.client.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Platform info for manifest list entries.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Platform(
        @JsonProperty("architecture") String architecture,
        @JsonProperty("os") String os,
        @JsonProperty("variant") String variant
) {
}

