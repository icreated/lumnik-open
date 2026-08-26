package io.lumnik.hub.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the fused-entity `_conflicts` ledger — {@code {"<col>": [{"src","value"}, ...]}} — into a
 * labelled, structured form for the detail surface. Each conflicted column is labelled from the
 * resolved view (falling back to its own name). {@code {}}/null/blank → no conflicts. Pure.
 */
public final class ConflictLedger {

    private static final ObjectMapper M = new ObjectMapper();

    private ConflictLedger() {}

    public record SourceValue(String src, String value) {}
    public record Conflict(String column, String label, List<SourceValue> sources) {}

    public static List<Conflict> parse(String json, ResolvedView rv) {
        if (json == null || json.isBlank()) return List.of();
        JsonNode root;
        try {
            root = M.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid _conflicts ledger: " + e.getMessage(), e);
        }
        List<Conflict> out = new ArrayList<>();
        root.fields().forEachRemaining(e -> {
            String column = e.getKey();
            List<SourceValue> sources = new ArrayList<>();
            for (JsonNode sv : e.getValue()) {
                sources.add(new SourceValue(text(sv, "src"), text(sv, "value")));
            }
            out.add(new Conflict(column, labelFor(column, rv), sources));
        });
        return out;
    }

    private static String labelFor(String column, ResolvedView rv) {
        return rv.columns().stream()
                .filter(c -> c.name().equals(column))
                .map(ResolvedView.Column::label)
                .findFirst()
                .orElse(column);
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }
}
