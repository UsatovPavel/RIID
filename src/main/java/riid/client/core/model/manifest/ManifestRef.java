package riid.client.core.model.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import riid.client.core.model.Platform;

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

