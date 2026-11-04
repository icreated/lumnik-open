package io.lumnik.hub.validator.common;

import io.lumnik.hub.json.JsonNodes;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;

public final class TargetBlockValidator {
    private TargetBlockValidator() {}

    /** What an index refusal teaches: the runtime drops a malformed entry in silence, so the shape is spelled out. */
    public static final String INDEXES_HINT =
        "indexes is a list of { columns: [...], unique?: bool } objects — e.g. indexes: [{ columns: [email] }]";

    /**
     * One index: a non-empty list of column names, and whether it is unique. The entry stays open — an extra
     * key is not accused — and a flag written empty ({@code unique:}) declares nothing, so it is not mistyped.
     * Shared by the csv target and the jdbc per-table definition, which read the same entries.
     */
    public static ConfigDef indexEntry() {
        return new ConfigDef()
            .require("columns", Type.LIST, Importance.HIGH, "Columns of the index, in order")
                .nonEmpty()
                .eachItemIs("a non-blank string", JsonNodes::nonBlank)
                .hint("e.g. columns: [email] or [bpartner_id, dateordered]")
            .optional("unique", Type.BOOLEAN, Importance.LOW, "Unique index (default false)", false);
    }

    public static ConfigDef configDef() {
        return new ConfigDef()
            // The scope is not a target concern: it lives at metadata.scopes (ScopesValidator).
            .optionalListOf("indexes", Importance.LOW, "Postgres indexes on the hub table", indexEntry())
                .hint(INDEXES_HINT)
            // C3: indexes is the whole runtime-read target surface — typos warn.
            .closed();
    }
}
