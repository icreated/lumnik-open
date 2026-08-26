package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.validator.ValidationError.Severity;

import java.util.*;

public final class EnvelopeValidator {

    public static final Set<String> SUPPORTED_API_VERSIONS = Set.of(
        "connectors.lumnik.io/v1"
    );

    private static final String API_VERSION = "apiVersion";

    private EnvelopeValidator() {}

    public static List<ValidationError> validate(JsonNode manifest) {
        List<ValidationError> errs = new ArrayList<>();

        String apiVersion = manifest.path(API_VERSION).asText();
        if (apiVersion.isBlank()) {
            errs.add(new ValidationError(API_VERSION, Severity.ERROR,
                "apiVersion is required",
                List.of("Use: apiVersion: connectors.lumnik.io/v1")));
        } else if (!SUPPORTED_API_VERSIONS.contains(apiVersion)) {
            errs.add(new ValidationError(API_VERSION, Severity.ERROR,
                "unsupported apiVersion: " + apiVersion,
                List.of("Supported: " + String.join(", ", SUPPORTED_API_VERSIONS))));
        }

        if (!"Connector".equals(manifest.path("kind").asText())) {
            errs.add(new ValidationError("kind", Severity.ERROR,
                "kind must be \"Connector\"", List.of()));
        }

        if (manifest.path("metadata").path("name").asText().isBlank()) {
            errs.add(new ValidationError("metadata.name", Severity.ERROR,
                "metadata.name is required (kebab-case)", List.of()));
        }

        if (manifest.path("metadata").path("connector_type").asText().isBlank()) {
            errs.add(new ValidationError("metadata.connector_type", Severity.ERROR,
                "metadata.connector_type is required (e.g. \"rest-generic\")", List.of()));
        }

        return errs;
    }
}
