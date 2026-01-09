package riid.client.core.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OCI/Docker manifest list (index).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ManifestIndex(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("mediaType") String mediaType,
        @JsonProperty("manifests") List<ManifestRef> manifests
) {
}

