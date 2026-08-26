package io.lumnik.hub.connector.mapping.writer;

import java.util.List;
import java.util.Optional;

/**
 * Specification for hybrid JSONB storage: promoted typed columns + raw {@code data JSONB} payload.
 *
 * @param table         Fully-qualified target table, e.g. {@code ext.stripe_charges}
 * @param uniqueKeyPath JsonPath to extract the external idempotency key from each item
 * @param promote       Columns to extract and store in typed Postgres columns
 */
public record HybridJsonbSpec(String table, String uniqueKeyPath, List<PromotedColumn> promote) {

    public record PromotedColumn(String name, String jsonPath, SqlType type, Optional<Transform> transform) {}

    public enum SqlType {
        TEXT("TEXT"),
        BIGINT("BIGINT"),
        INTEGER("INTEGER"),
        BOOLEAN("BOOLEAN"),
        DECIMAL("NUMERIC"),
        TIMESTAMPTZ("TIMESTAMPTZ"),
        DATE("DATE"),
        JSONB("JSONB");

        public final String ddl;

        SqlType(String ddl) { this.ddl = ddl; }
    }

    public enum Transform {
        /** Interprets the source value as Unix epoch SECONDS. For milliseconds, divide by 1000 upstream or add FROM_EPOCH_MILLIS in v2. */
        FROM_EPOCH,
        PARSE_ISO8601,
        TO_LOWER,
        TO_UPPER,
        TRIM,
        TO_JSON_STRING
    }
}
