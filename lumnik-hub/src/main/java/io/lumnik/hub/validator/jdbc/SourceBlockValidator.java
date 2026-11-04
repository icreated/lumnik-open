package io.lumnik.hub.validator.jdbc;

import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ConfigDefValidators;
import io.lumnik.hub.validator.common.TargetBlockValidator;

import java.util.List;

public final class SourceBlockValidator {
    private SourceBlockValidator() {}

    public static ConfigDef configDef() {
        // The shape of a table is DESCRIBED here, not enforced: jdbc checks a table laid over `defaults`
        // (JdbcConnectorValidator.validateTable runs perTableDef on the merged node), and a check on the table as written
        // would refuse one whose schema or key comes from there — and check the rest twice.
        return new ConfigDef()
            .requireListOf("tables", Importance.HIGH,
                "List of tables to ingest (at least one)", perTableDef().describesOnly())
                .nonEmpty()
                .hint("Add a table: { name: 'invoices', mode: 'full', pk_columns: ['id'] }");
    }

    /**
     * What {@code spec.defaults} may carry: the keys of a table, each optional, but its name and the hub table it lands in
     * — a default for those would name every table alike. Described, not enforced: the merged table is what is checked.
     */
    public static ConfigDef defaultsDef() {
        return new ConfigDef().optionalAll(perTableDef()).without(List.of("name", "target")).describesOnly();
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
            .requireEnum("mode", Importance.HIGH, "Ingest mode (here or in defaults)", "watermark", "full")
            // Required because the run reads it unconditionally ("Missing config key: schema", #1243);
            // checked on the defaults-merged table, so `defaults.schema` satisfies it.
            .require("schema", Type.STRING, Importance.HIGH, "Table schema (here or in defaults)")
            .optional("watermark_column", Type.STRING, Importance.MEDIUM,
                "The column a watermark read moves forward on", null)
                .requiredWhen("mode", "watermark")
                .hint("Example: watermark_column: updated_at")
            // Checked on the defaults-merged table, like schema: `defaults.pk_columns` satisfies it.
            .require("pk_columns", Type.LIST, Importance.HIGH, "Primary key columns (non-empty; here or in defaults)")
                .nonEmpty()
                .hint("Example: pk_columns: [id]")
            // chunk_size was a dead knob — validated for months while no execution code ever read it (the
            // engine always reads type.defaultChunkSize() = 500). Refused loudly rather than accepted and
            // ignored, so no manifest can claim a lever that does not exist (decision #286; the CSV validator
            // warns for its dead knob — jdbc refuses because its doc sold the knob as live). Written empty it
            // claims nothing, and is not refused.
            .refused("chunk_size", "chunk_size is not a knob — jdbc chunking is fixed at 500 rows per read; remove it",
                "The engine reads its chunk size from the connector type, never from the manifest")
            .optionalListOf("indexes", Importance.LOW,
                "Secondary indexes on the target table: [{columns: [...], unique?: bool (default false)}]",
                TargetBlockValidator.indexEntry())
                .hint(TargetBlockValidator.INDEXES_HINT)
            .optional("rename", Type.NESTED, Importance.LOW,
                "Rename source columns before they become hub columns: {source: target}", null)
            .closed();
    }
}
