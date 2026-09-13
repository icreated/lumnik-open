package io.lumnik.hub.api.connector.handler;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.lumnik.hub.validator.ValidationError;

import java.util.List;
import java.util.UUID;

/**
 * What an apply answers: what was written, and what the validator had to say about it.
 *
 * <p>The advisories travel here because this is the body the integrator's one command prints.
 * {@code lm validate} showed them and {@code lm apply} — the documented path, and the one a CI
 * job runs — did not: the resource computed the {@code ValidationResult}, refused on errors and
 * dropped the warnings (#531). A handler only knows what it applied, so it builds the three-field
 * form and the resource adds the rest with {@link #withWarnings}.
 *
 * <p>Absent rather than empty when there is nothing to say: the one-line JSON body the connector
 * docs print is what it always was, and a reader never has to tell {@code []} from "not checked".
 */
public record ApplyResult(
    String name,
    UUID connectorId,
    int endpointsApplied,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ValidationError> warnings
) {
    public ApplyResult {
        if (warnings == null) warnings = List.of();
    }

    public ApplyResult(String name, UUID connectorId, int endpointsApplied) {
        this(name, connectorId, endpointsApplied, List.of());
    }

    public ApplyResult withWarnings(List<ValidationError> advisories) {
        return new ApplyResult(name, connectorId, endpointsApplied, advisories);
    }
}
