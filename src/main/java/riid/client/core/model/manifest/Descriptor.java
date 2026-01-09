package riid.client.core.model.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OCI/Docker descriptor for manifest entries (layers/config/subjects).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Descriptor(
        @JsonProperty("mediaType") String mediaType,
        @JsonProperty("digest") String digest,
        @JsonProperty("size") long size
) {
}

