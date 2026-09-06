package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.sql.SqlIdentifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Specification for hybrid JSONB storage: promoted typed columns + raw {@code data JSONB} payload.
 *
 * @param table         Fully-qualified target table, e.g. {@code ext.stripe_charges}
 * @param uniqueKeyPath JsonPath to extract the external idempotency key from each item
 * @param promote       Columns to extract and store in typed Postgres columns
 */
public record HybridJsonbSpec(String table, String uniqueKeyPath, List<PromotedColumn> promote) {

    /**
     * The one parser of the endpoint {@code target} block — every reader consumes THIS,
     * never a private re-parse (a third inline copy once silently dropped {@code transform}).
     * Carries the DDL-injection guard: {@code table} goes verbatim into admin-datasource DDL,
     * so this factory must not trust its caller either.
     */
    @SuppressWarnings("unchecked")
    public static HybridJsonbSpec fromTargetConfig(Map<String, Object> t) {
        if (t == null) throw new IllegalArgumentException("endpoint config missing 'target'");
        String table = (String) t.get("table");
        if (!SqlIdentifiers.isPlainQualifiedIdentifier(table)) {
            throw new IllegalArgumentException(
                "target.table must be a plain identifier or schema.table pair (letters, digits, underscore; "
                    + "not starting with a digit): " + table);
        }
        String key = (String) t.get("unique_key");
        List<Map<String, Object>> rawPromote =
            (List<Map<String, Object>>) t.getOrDefault("promote", List.of());
        List<PromotedColumn> promote = new ArrayList<>();
        for (Map<String, Object> p : rawPromote) {
            Object transformRaw = p.get("transform");
            promote.add(new PromotedColumn(
                (String) p.get("name"),
                (String) p.get("path"),
                SqlType.valueOf(String.valueOf(p.get("type")).toUpperCase()),
                transformRaw != null
                    ? Optional.of(Transform.valueOf(String.valueOf(transformRaw).toUpperCase()))
                    : Optional.empty()));
        }
        return new HybridJsonbSpec(table, key, promote);
    }

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
