package riid.client.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Registry error response wrapper.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        @JsonProperty("errors") List<Item> errors
) {
    public ErrorResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("detail") Object detail
    ) {}
}

