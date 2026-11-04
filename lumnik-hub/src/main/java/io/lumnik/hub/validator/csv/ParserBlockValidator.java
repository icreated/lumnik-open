package io.lumnik.hub.validator.csv;

import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;

import java.util.List;

public final class ParserBlockValidator {
    private ParserBlockValidator() {}

    public static ConfigDef configDef() {
        return new ConfigDef()
            .optional("delimiter", Type.STRING, Importance.LOW,
                "Field delimiter (single char) — auto-detected if absent", null)
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
