package io.lumnik.extraction.spi.search;

import java.util.List;

/**
 * The provider-neutral shape of a {@code kind: SearchIndex} declaration, everything a provider needs to
 * create or update its own schema/analyzer/mapping *before* any document arrives — entity name, text
 * analysis language, and every field's mode/facet. Built fresh from the exact {@code manifest_yaml} a
 * rebuild just re-read, never from a value discovery cached earlier.
 */
public record ProjectionDefinition(String entity, String language, List<ProjectionField> fields) {

    public ProjectionDefinition {
        if (entity == null || entity.isBlank())
            throw new IllegalArgumentException("entity must not be blank");
        if (language == null || language.isBlank())
            throw new IllegalArgumentException("language must not be blank");
        fields = List.copyOf(fields);
    }
}
