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
            .optional("columns", Type.LIST, Importance.MEDIUM,
                "Column names in file order — REQUIRED when has_header: false "
                    + "(cross-checked by the csv validator)", null)
            // C3: every runtime-accepted parser key is declared above — typos warn.
            .closed();
    }
}
