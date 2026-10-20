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
                "Table name — also names the hub table connector.t_<name>, unless target names it",
                // The runtime refuses a non-identifier before it composes `connector.t_<name>` DDL
                // (JdbcConnectorType.dumpSpec), but dumpSpec is reached from writer(), i.e. DURING a
                // run — and nothing on the jdbc path calls validateConfig. Declared here because this
                // is the def BOTH doors read: `lm validate` and apply refuse together, or a bad name
                // persists and kills the first run it is given.
                ConfigDefValidators.plainIdentifier("the hub table connector.t_<name>"))
            .optionalCustom("target", Type.STRING, Importance.MEDIUM,
                "Names the hub table connector.t_<target> in place of the source table's name (#1199)",
                ConfigDefValidators.plainIdentifier("the hub table connector.t_<target>"))
            .requireEnum("mode", Importance.HIGH, "Ingest mode", "watermark", "full")
            // Required because the run reads it unconditionally ("Missing config key: schema", #1243);
            // checked on the defaults-merged table, so `defaults.schema` satisfies it.
            .require("schema", Type.STRING, Importance.HIGH, "Table schema (here or in defaults)")
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
