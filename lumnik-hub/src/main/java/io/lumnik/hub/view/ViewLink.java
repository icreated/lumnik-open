package io.lumnik.hub.view;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * One {@code spec.links} entry of a kind:View (#1050): the cell of {@code column} leads either to another
 * saved View with {@code filters} installed ({@code targetView}), or to an external page ({@code url}).
 * Exactly one destination. Values are templates: {@code {col}} is replaced, by the client, with that
 * column's value on the clicked row — nothing else is ever interpolated.
 *
 * <p>Serialized as is into the render response, so the PWA (which has no YAML parser) can follow it.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ViewLink(String column, String targetView, Map<String, String> filters, String url) {

    public ViewLink {
        // order is the manifest's: it is the order of the filter chips and of the apply warnings
        filters = filters == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(filters));
    }
}
