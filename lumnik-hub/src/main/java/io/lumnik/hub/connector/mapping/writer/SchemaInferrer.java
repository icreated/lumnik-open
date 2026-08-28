package io.lumnik.hub.connector.mapping.writer;

import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Infers a PostgreSQL column type per field from a sample of rows.
 * All non-null values for a field must agree, otherwise TEXT is used.
 * Null-only fields → TEXT.
 */
@ApplicationScoped
public class SchemaInferrer {

  public Map<String, String> infer(List<Map<String, Object>> rows) {
    return infer(rows, Map.of());
  }

  /**
   * Infer column types, falling back to {@code sourceTypeHints} (e.g. JDBC source column types)
   * <strong>only</strong> for columns the sample could not type because every value was NULL.
   * Columns with an observed Java-type signal always win over the hint — this preserves the
   * setObject contract (a hook may have stringified a numeric-looking column), and limits the
   * hint's effect to exactly the bug it fixes: a sparse column whose first batch is all-NULL
   * would otherwise freeze to TEXT and break cross-table joins ({@code numeric = text}).
   */
  public Map<String, String> infer(List<Map<String, Object>> rows, Map<String, String> sourceTypeHints) {
    Map<String, String> schema = new HashMap<>();
    if (rows.isEmpty()) return schema;

    Set<String> allCols = new LinkedHashSet<>();
    for (var r : rows) allCols.addAll(r.keySet());

    for (String col : allCols) {
      schema.put(col, inferColumn(col, rows, sourceTypeHints));
    }
    return schema;
  }

  /**
   * Candidate column types in priority order. A column gets the first type whose predicate holds
   * for <em>all</em> of its non-null sampled values; if none do, it falls back to TEXT.
   */
  private enum Candidate {
    TIMESTAMP("TIMESTAMPTZ",   SchemaInferrer::isTimestamp),
    DATE     ("DATE",          SchemaInferrer::isDate),
    BOOLEAN  ("BOOLEAN",       SchemaInferrer::isBool),
    NUMERIC  ("NUMERIC(18,4)", SchemaInferrer::isNumber),
    LIST     ("JSONB",         v -> v instanceof List<?>);

    final String ddl;
    final Predicate<Object> matches;
    Candidate(String ddl, Predicate<Object> matches) { this.ddl = ddl; this.matches = matches; }
  }

  private String inferColumn(String col, List<Map<String, Object>> rows, Map<String, String> hints) {
    List<Object> values = rows.stream().map(r -> r.get(col)).filter(Objects::nonNull).toList();
    if (values.isEmpty()) return hints.getOrDefault(col, "TEXT");

    for (Candidate c : Candidate.values()) {
      if (values.stream().allMatch(c.matches)) return c.ddl;
    }
    return "TEXT";
  }

  /**
   * Maps a {@link java.sql.Types} code to the PostgreSQL DDL type string this inferrer emits,
   * so source-declared types and value-inferred types stay consistent. Numerics collapse to
   * NUMERIC(18,4) (matching value inference of {@link Number}); unknown codes → TEXT.
   */
  public static String pgTypeForSqlType(int sqlType) {
    return switch (sqlType) {
      case Types.NUMERIC, Types.DECIMAL,
           Types.TINYINT, Types.SMALLINT, Types.INTEGER,
           Types.BIGINT, Types.REAL, Types.FLOAT,
           Types.DOUBLE                              -> "NUMERIC(18,4)";
      case Types.TIMESTAMP,
           Types.TIMESTAMP_WITH_TIMEZONE             -> "TIMESTAMPTZ";
      case Types.DATE                                -> "DATE";
      case Types.BOOLEAN, Types.BIT         -> "BOOLEAN";
      default                                                 -> "TEXT";
    };
  }

  // Inference is by the value's JAVA TYPE, not its string content: the writer binds values via
  // PreparedStatement.setObject(value), so the column type must match what the driver sends for
  // that Java type. Parsing string content (e.g. "5" → numeric) would mis-type a varchar column
  // whose value happens to look numeric (legacy-ERP char columns like priorityrule), then fail the
  // INSERT ("column is numeric but expression is character varying"). JDBC getObject returns
  // java.sql.Timestamp/Date for temporal columns, BigDecimal for numerics, String for char.

  private static boolean isTimestamp(Object v) {
    return v instanceof Instant || v instanceof LocalDateTime
        || v instanceof OffsetDateTime || v instanceof ZonedDateTime
        || v instanceof Timestamp;
  }

  private static boolean isDate(Object v) {
    return v instanceof LocalDate || v instanceof Date;
  }

  private static boolean isBool(Object v) {
    return v instanceof Boolean;
  }

  private static boolean isNumber(Object v) {
    return v instanceof Number;
  }
}
