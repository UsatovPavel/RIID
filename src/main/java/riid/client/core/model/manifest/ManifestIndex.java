package riid.client.core.model.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.List;

/**
 * OCI/Docker manifest list (index).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ManifestIndex(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("mediaType") String mediaType,
        @JsonProperty("manifests")
        @JsonSetter(nulls = Nulls.AS_EMPTY)
        List<ManifestRef> manifests
) {
    public ManifestIndex {
        manifests = manifests == null ? List.of() : List.copyOf(manifests);
    }
}

