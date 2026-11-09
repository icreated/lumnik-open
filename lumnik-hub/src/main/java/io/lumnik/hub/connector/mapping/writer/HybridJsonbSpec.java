package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.hub.sql.SqlIdentifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Specification for hybrid JSONB storage: promoted typed columns + raw {@code data JSONB} payload.
 *
 * @param table         Fully-qualified target table, e.g. {@code ext.stripe_charges}
 * @param uniqueKeyPath JsonPath to extract the external idempotency key from each item
 * @param promote       Columns to extract and store in typed Postgres columns
 * @param uniqueKeyTransform the transform the key's value goes through, as a promote entry's (#1630)
 */
public record HybridJsonbSpec(String table, String uniqueKeyPath, List<PromotedColumn> promote,
                              Optional<Transform> uniqueKeyTransform) {

    /** The columns of a hybrid table before the promoted ones, in table order. */
    static final List<TableColumn> LEADING_COLUMNS = List.of(
        new TableColumn("id", "BIGSERIAL PRIMARY KEY"),
        new TableColumn("tenant_id", "BIGINT NOT NULL"),
        new TableColumn("external_id", "TEXT NOT NULL"));

    /** The columns after them, in table order. */
    static final List<TableColumn> TRAILING_COLUMNS = List.of(
        new TableColumn("row_hash", "TEXT"),
        new TableColumn("data", "JSONB NOT NULL"),
        new TableColumn("indexed_at", "TIMESTAMPTZ DEFAULT now()"),
        new TableColumn("connector_id", "UUID NOT NULL"),
        new TableColumn("endpoint_id", "UUID NOT NULL"));

    /**
     * The columns every hybrid table carries around the promoted ones — the names of {@link #LEADING_COLUMNS} and
     * {@link #TRAILING_COLUMNS}, which {@link HybridJsonbWriter#createTableSql} writes. A promote entry named like one
     * would die at the first run on "column specified more than once" (#1781).
     */
    public static final Set<String> RESERVED_COLUMNS = TableColumn.namesOf(LEADING_COLUMNS, TRAILING_COLUMNS);

    public HybridJsonbSpec(String table, String uniqueKeyPath, List<PromotedColumn> promote) {
        this(table, uniqueKeyPath, promote, Optional.empty());
    }

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
        // A path, or { path, transform } (#1630): Spring Data REST names an item only by its self link.
        Object rawKey = t.get("unique_key");
        Map<String, Object> keyObject = rawKey instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String key = rawKey instanceof Map ? (String) keyObject.get("path") : (String) rawKey;
        List<PromotedColumn> promote = promotedColumns((List<Map<String, Object>>) t.getOrDefault("promote", List.of()));
        return new HybridJsonbSpec(table, key, promote, transform(keyObject.get("transform")));
    }

    /** The one parser of a {@code promote} list — rest's {@code target.promote}, kafka's {@code spec.promote} (#1777). */
    public static List<PromotedColumn> promotedColumns(List<Map<String, Object>> rawPromote) {
        List<PromotedColumn> promote = new ArrayList<>();
        for (Map<String, Object> p : rawPromote) {
            promote.add(new PromotedColumn(
                (String) p.get("name"),
                (String) p.get("path"),
                SqlType.valueOf(String.valueOf(p.get("type")).toUpperCase(Locale.ROOT)),
                transform(p.get("transform"))));
        }
        return promote;
    }

    private static Optional<Transform> transform(Object raw) {
        return raw != null ? Optional.of(Transform.valueOf(String.valueOf(raw).toUpperCase(Locale.ROOT))) : Optional.empty();
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
        TO_JSON_STRING,
        /** A number with a unit suffix ({@code 100m}, {@code 64Mi}, {@code 99827146n}) in its base unit (#1618). */
        PARSE_QUANTITY,
        /** The last segment of a link's path, percent-decoded — the id of a resource named by its URL (#1630). */
        LAST_PATH_SEGMENT,
        /** An OData v2 date, {@code /Date(<ms>)/} or {@code /Date(<ms>±<minutes>)/}, as the UTC instant it names (#1826). */
        FROM_ODATA_DATE
    }
}
