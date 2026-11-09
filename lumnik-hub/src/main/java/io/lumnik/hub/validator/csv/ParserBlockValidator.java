package io.lumnik.hub.validator.csv;

import io.lumnik.hub.connector.csv.CsvFileConnectorType;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;

import java.util.List;
import java.util.Optional;

public final class ParserBlockValidator {
    private ParserBlockValidator() {}

    public static ConfigDef configDef() {
        return new ConfigDef()
            .optional("delimiter", Type.STRING, Importance.LOW,
                "Field delimiter (single char) — auto-detected if absent", null)
            .optional("escape", Type.STRING, Importance.LOW,
                "Escape character for a quote inside a quoted value (single char, e.g. \\) — "
                    + "absent means the quote is doubled (RFC 4180); DisplayTag and MySQL OUTFILE use a backslash", null)
                .check(v -> v.isTextual() && v.asText().length() == 1 ? Optional.empty()
                    : Optional.of("must be a single character"))
            .optional("encoding", Type.STRING, Importance.LOW,
                "Character encoding — auto-detected if absent", null)
            .optional("has_header", Type.BOOLEAN, Importance.MEDIUM,
                "First row is header", true)
            .optional("bom_strip", Type.BOOLEAN, Importance.LOW,
                "Strip BOM from UTF-8", true)
            .optional("null_values", Type.LIST, Importance.LOW,
                "Strings treated as null",
                List.of("", "NULL", "null", "#N/A"))
            .optional("lines", Type.BOOLEAN, Importance.MEDIUM,
                "One line = one row, one column (`line`): no delimiter, no quoting — fixed-width files, logs", false)
            .optional("short_rows", Type.STRING, Importance.LOW,
                "A line with fewer values than the declared columns: error (dead letter, default) or "
                    + "skip-leading (skipped while the table has not begun: a key;value block before it)", null)
                .oneOf(CsvFileConnectorType.SKIP_LEADING, "error")
            .optional("columns", Type.LIST, Importance.MEDIUM,
                "Column names in file order", null)
                // lines: true names its one column itself; the parser rule that refuses has_header beside it answers for that.
                .requiredWhen("has_header is false and lines is off",
                    p -> p.path("has_header").isBoolean() && !p.path("has_header").asBoolean()
                        && !p.path("lines").asBoolean(false))
                .hint("Add parser.columns: [code, name, ...] in file order")
            // C3: every runtime-accepted parser key is declared above — typos warn.
            .closed();
    }
}
