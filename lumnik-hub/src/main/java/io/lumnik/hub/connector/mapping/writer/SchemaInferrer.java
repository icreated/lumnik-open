package io.lumnik.hub.connector.mapping.writer;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Infers a PostgreSQL column type per field from a sample of rows.
 * All non-null values for a field must agree, otherwise TEXT is used.
 *
 * <p>A null-only field takes its {@code sourceTypeHints} entry when there is one, and TEXT when
 * there is not. This line used to read "Null-only fields → TEXT" flatly; the hint feature made
 * that false and the sentence did not notice. {@code SchemaTypingInventoryTest} now pins both
 * halves, so the doc cannot drift ahead of the code a second time.
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
    Map<String, String> schema = new LinkedHashMap<>();
    if (rows.isEmpty()) return schema;

    Set<String> allCols = new LinkedHashSet<>();
    for (var r : rows) allCols.addAll(r.keySet());

    for (String col : allCols) {
      schema.put(col, inferColumn(col, rows, sourceTypeHints));
    }
    return schema;
  }

  /**
   * The two numeric DDL strings, named because {@code DynamicTableWriter} must agree with them
   * byte for byte: it widens a registered {@link #BIGINT} column to {@link #NUMERIC} when a later
   * chunk carries a fraction, and compares the registered string to decide. A second spelling of
   * either in that class would silently stop widening anything.
   */
  static final String BIGINT = "BIGINT";
  static final String NUMERIC = "NUMERIC(18,4)";

  /**
   * Candidate column types in priority order. A column gets the first type whose predicate holds
   * for <em>all</em> of its non-null sampled values; if none do, it falls back to TEXT.
   */
  private enum Candidate {
    TIMESTAMP("TIMESTAMPTZ",   SchemaInferrer::isTimestamp),
    DATE     ("DATE",          SchemaInferrer::isDate),
    BOOLEAN  ("BOOLEAN",       SchemaInferrer::isBool),
    // Before NUMERIC, and that order is the whole rule: every integral value is also a Number, so
    // a column reaches NUMERIC only once something in the sample is NOT whole. A whole number then
    // reads as one everywhere downstream — psql, a kind:View, an ask answer — instead of
    // 1000.0000 (#497). Narrowing is safe because the writer can still widen: see
    // DynamicTableWriter.widenings, the one direction a later chunk is allowed to move a column.
    INTEGRAL (SchemaInferrer.BIGINT,  SchemaInferrer::isIntegral),
    NUMERIC  (SchemaInferrer.NUMERIC, SchemaInferrer::isNumber),
    // Both nested shapes, one type. A Map used to fall through to TEXT while a List got JSONB —
    // an asymmetry with no reason behind it, and both were unwritable anyway: the List's JSON
    // string was sent as varchar (Postgres refuses it on a jsonb column) and the Map was sent to
    // pgJDBC's hstore mapping ("No hstore extension installed"). DynamicTableWriter now binds both
    // with Types.OTHER, so the type this names is the type that lands.
    NESTED   ("JSONB",         v -> v instanceof List<?> || v instanceof Map<?, ?>);

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
   * so source-declared types and value-inferred types stay consistent: a source's INTEGER answers
   * BIGINT because an {@code Integer} value does, and a source's DECIMAL answers NUMERIC(18,4)
   * because a {@code BigDecimal} does — a declared scale is the source's own opinion and is kept.
   * Unknown codes → TEXT. {@code SchemaTypingInventoryTest} enforces the agreement.
   */
  public static String pgTypeForSqlType(int sqlType) {
    return switch (sqlType) {
      case Types.TINYINT, Types.SMALLINT,
           Types.INTEGER, Types.BIGINT               -> BIGINT;
      case Types.NUMERIC, Types.DECIMAL,
           Types.REAL, Types.FLOAT,
           Types.DOUBLE                              -> NUMERIC;
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

  /**
   * A number with no fractional part BY TYPE, never by value. A {@code Double} of 42.0 is not one:
   * the type is what pgJDBC binds, and a producer that sent 42.0 once will send 42.5 next.
   * {@code BigDecimal} stays out too — JDBC returns it for a column the source DECLARED numeric,
   * scale included.
   */
  static boolean isIntegral(Object v) {
    return v instanceof Byte || v instanceof Short || v instanceof Integer
        || v instanceof Long || v instanceof BigInteger;
  }
}
