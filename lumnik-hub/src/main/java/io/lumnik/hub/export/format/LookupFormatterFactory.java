package io.lumnik.hub.export.format;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code lookup}: our codes to the partner's, through an inline table. {@code on-missing} is
 * {@code keep} (the value as it is), {@code error} (the run fails) or any other text (that
 * literal). Unlike the inbound hook there is no {@code null}: emptying a column the partner
 * expects, silently, is not offered.
 */
@ApplicationScoped
public class LookupFormatterFactory implements ColumnFormatterFactory {

    private static final Set<String> PARAMS = Set.of("table", "on-missing");

    @Override
    public String kind() {
        return "lookup";
    }

    @Override
    public ColumnFormatter compile(JsonNode params) {
        FormatterParams.refuseUnknown(params, PARAMS);
        JsonNode tableNode = params.get("table");
        if (tableNode == null) throw new IllegalArgumentException("table is required");
        if (!tableNode.isObject()) throw new IllegalArgumentException("table must be a mapping");
        if (tableNode.isEmpty()) throw new IllegalArgumentException("table must not be empty");
        Map<String, String> table = new HashMap<>();
        tableNode.properties().forEach(e -> {
            if (!e.getValue().isTextual())
                throw new IllegalArgumentException("table." + e.getKey() + " must be text (quote it)");
            table.put(e.getKey(), e.getValue().asText());
        });

        JsonNode missingNode = params.get("on-missing");
        if (missingNode != null && missingNode.isNull())
            throw new IllegalArgumentException("on-missing: null is not offered — the partner would get "
                + "an empty column; use keep, error or a literal");
        String onMissing = FormatterParams.text(params, "on-missing");
        if (onMissing != null && onMissing.isEmpty())
            throw new IllegalArgumentException("on-missing: an empty literal is not offered — the partner "
                + "would get empty cells; use keep, error or a non-empty literal");
        if (onMissing == null || onMissing.equals("keep"))
            return value -> table.getOrDefault(value, value);
        if (onMissing.equals("error"))
            return value -> {
                String mapped = table.get(value);
                if (mapped == null) throw new ColumnFormatException("no entry in table for this value");
                return mapped;
            };
        return value -> table.getOrDefault(value, onMissing);
    }
}
