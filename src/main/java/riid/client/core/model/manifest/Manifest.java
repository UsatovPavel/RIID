package riid.client.core.model.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OCI/Docker image manifest (schema2).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Manifest(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("mediaType") String mediaType,
        @JsonProperty("config") Descriptor config,
        @JsonProperty("layers") List<Descriptor> layers
) {
}

