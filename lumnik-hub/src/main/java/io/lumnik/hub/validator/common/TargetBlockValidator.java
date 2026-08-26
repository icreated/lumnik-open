package io.lumnik.hub.validator.common;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationError.Severity;

import java.util.ArrayList;
import java.util.List;

public final class TargetBlockValidator {
    private TargetBlockValidator() {}

    public static ConfigDef configDef() {
        return new ConfigDef()
            .optional("tags", Type.LIST, Importance.MEDIUM,
                "RAG scope tags — enables tag-based filtering", List.of())
            .optional("indexes", Type.LIST, Importance.LOW,
                "Postgres indexes on the hub table", List.of())
            // C3: tags + indexes are the whole runtime-read target surface — typos warn.
            .closed();
    }

    /**
     * Validates the structure of an {@code indexes} array element-by-element. ConfigDef only checks
     * that {@code indexes} is a LIST; without this, malformed entries (missing/empty columns,
     * non-string column names, non-boolean {@code unique}) pass validation and are then silently
     * dropped by the runtime parsers — the user gets a "valid" config with missing indexes. This
     * fails fast with a clear per-entry error instead. A missing/null node is valid (indexes optional).
     *
     * @param indexes  the {@code indexes} JSON node (may be missing/null)
     * @param basePath error path prefix, e.g. {@code "spec.target.indexes"} or
     *                 {@code "spec.source.tables[0].indexes"}
     */
    public static List<ValidationError> validateIndexes(JsonNode indexes, String basePath) {
        List<ValidationError> errs = new ArrayList<>();
        if (indexes == null || indexes.isMissingNode() || indexes.isNull()) return errs;
        if (!indexes.isArray()) {
            errs.add(new ValidationError(basePath, Severity.ERROR,
                "indexes must be a list of { columns: [...], unique?: bool } objects",
                List.of("Example: indexes: [{ columns: [email] }]")));
            return errs;
        }
        for (int i = 0; i < indexes.size(); i++) {
            validateIndexEntry(indexes.get(i), basePath + "[" + i + "]", errs);
        }
        return errs;
    }

    private static void validateIndexEntry(JsonNode e, String p, List<ValidationError> errs) {
        if (!e.isObject()) {
            errs.add(new ValidationError(p, Severity.ERROR,
                "index entry must be an object with a 'columns' list",
                List.of("e.g. { columns: [email] }")));
            return;
        }
        JsonNode cols = e.path("columns");
        if (!cols.isArray() || cols.isEmpty()) {
            errs.add(new ValidationError(p + ".columns", Severity.ERROR,
                "index requires a non-empty 'columns' list",
                List.of("e.g. columns: [email] or [bpartner_id, dateordered]")));
            return;
        }
        for (int j = 0; j < cols.size(); j++) {
            JsonNode col = cols.get(j);
            if (!col.isTextual() || col.asText().isBlank()) {
                errs.add(new ValidationError(p + ".columns[" + j + "]", Severity.ERROR,
                    "column name must be a non-blank string", List.of()));
            }
        }
        JsonNode uniq = e.path("unique");
        if (!uniq.isMissingNode() && !uniq.isBoolean()) {
            errs.add(new ValidationError(p + ".unique", Severity.ERROR,
                "'unique' must be a boolean (true/false)", List.of()));
        }
    }
}
