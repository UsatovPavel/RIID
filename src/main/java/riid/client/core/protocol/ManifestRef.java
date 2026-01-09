package riid.client.core.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Entry in a manifest list / index.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ManifestRef(
        @JsonProperty("mediaType") String mediaType,
        @JsonProperty("digest") String digest,
        @JsonProperty("size") long size,
        @JsonProperty("platform") Platform platform
) {
}

