package io.lumnik.hub.validator.jdbc;

import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ConfigDefValidators;

public final class SourceBlockValidator {
    private SourceBlockValidator() {}

    public static ConfigDef configDef() {
        return new ConfigDef()
            .require("tables", Type.LIST, Importance.HIGH,
                "List of tables to ingest (at least one)");
    }

    public static ConfigDef perTableDef() {
        return new ConfigDef()
            .requireCustom("name", Type.STRING, Importance.HIGH,
                "Table name — also names the hub table connector.t_<name>",
                // The runtime refuses a non-identifier before it composes `connector.t_<name>` DDL
                // (JdbcConnectorType.dumpSpec), but dumpSpec is reached from writer(), i.e. DURING a
                // run — and nothing on the jdbc path calls validateConfig. Declared here because this
                // is the def BOTH doors read: `lm validate` and apply refuse together, or a bad name
                // persists and kills the first run it is given.
                ConfigDefValidators.plainIdentifier("the hub table connector.t_<name>"))
            .requireEnum("mode", Importance.HIGH, "Ingest mode", "watermark", "full")
            .optional("schema", Type.STRING, Importance.MEDIUM, "Table schema", null)
            .optional("watermark_column", Type.STRING, Importance.MEDIUM,
                "Required when mode=watermark", null)
            .optional("pk_columns", Type.LIST, Importance.HIGH,
                "Primary key columns (non-empty)", null)
            .optional("chunk_size", Type.INT, Importance.LOW,
                "Not a knob — refused by validation (jdbc chunking is fixed at 500)", null)
            .optionalHinted("indexes", Type.LIST, Importance.LOW,
                "Secondary indexes on the target table: [{columns: [...], unique?: bool (default false)}]",
                null, "indexes is a list of { columns: [...], unique?: bool } objects — e.g. indexes: [{ columns: [email] }]")
            .optional("rename", Type.NESTED, Importance.LOW,
                "Rename source columns before they become hub columns: {source: target}", null)
            .closed();
    }
}
