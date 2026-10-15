package io.lumnik.hub.export.format;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A formatter kind, as {@code spec.columns[i].format[j]} names it. Same shape as the export's
 * {@code FileDestinationFactory}: a bean found through {@code Instance<>} by its {@link #kind()},
 * so a new formatter is one class, and the parser, the registry and the streamer stay as they are.
 */
public interface ColumnFormatterFactory {

    String kind();

    /**
     * Checks {@code params} (always a mapping, possibly empty) and returns the compiled step. A
     * refusal is an {@link IllegalArgumentException} worded relative to the step ({@code "pattern
     * is required"}); the registry prefixes the field path. Called at apply and again by every run.
     */
    ColumnFormatter compile(JsonNode params);
}
