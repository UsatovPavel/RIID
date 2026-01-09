package riid.client.core.model.manifest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Tag listing response.
 */
public record TagList(
        @JsonProperty("name") String name,
        @JsonProperty("tags") List<String> tags
) {
}

