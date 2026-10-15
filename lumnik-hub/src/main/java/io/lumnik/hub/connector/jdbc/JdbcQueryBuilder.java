package io.lumnik.hub.connector.jdbc;

import io.lumnik.jdbc.spi.Comparison;
import io.lumnik.jdbc.spi.JdbcDialect;
import io.lumnik.jdbc.spi.KeyComparison;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pure builder of the SELECT used by JdbcChunkReader. Kept separate so it can be
 * unit-tested without any database.
 *
 * <p>Both modes page on a keyset: the rows are ordered by the key columns and a resume reads the
 * rows after {@code (lastValues)}, a lexicographic comparison the DIALECT expresses (a row-value
 * tuple where the database has one, an {@code OR/AND} expansion where it does not) together with
 * the plan saying which key value each {@code ?} takes. The key is the whole declared
 * primary key — reading only its first column loses every row of the group that straddles a
 * chunk boundary. WATERMARK prepends the watermark column, which is what makes the primary key
 * its tiebreak. For a single key column the row form degenerates to a plain scalar comparison.
 */
public class JdbcQueryBuilder {

  public enum Mode { WATERMARK, FULL }

  /**
   * A SELECT with the bind plans of its two comparisons: {@code cursorPlan} for the {@code >} clause,
   * {@code ceilingPlan} for the {@code <=} one, each empty when its clause is absent. The reader
   * binds the cursor and ceiling values through them, in the key order of {@link #keyColumns}.
   */
  public record Select(String sql, List<Integer> cursorPlan, List<Integer> ceilingPlan) {}

  private final JdbcDialect dialect;

  public JdbcQueryBuilder(JdbcDialect dialect) {
    this.dialect = dialect;
  }

  /**
   * The columns this query orders and pages on, in order. The reader binds the cursor values in
   * exactly this order — one list, so the SELECT and its parameters cannot drift apart.
   */
  public static List<String> keyColumns(String watermarkColumn, List<String> pkColumns, Mode mode) {
    if (mode != Mode.WATERMARK || watermarkColumn == null) return List.copyOf(pkColumns);
    List<String> keys = new ArrayList<>(pkColumns.size() + 1);
    keys.add(watermarkColumn);
    keys.addAll(pkColumns);
    return List.copyOf(keys);
  }

  /**
   * @param cursor where to resume from, driving the {@code >} clause — null or empty means the start
   * @param bound  the run's ceiling, driving the {@code <=} clause — null means unbounded, which is
   *               how this query read before runs had a target. Its VALUES are bound by the reader,
   *               after the cursor's, in this same key order.
   */
  public Select buildSelect(String schema, String table, String watermarkColumn,
                            List<String> pkColumns, JdbcCursor cursor, JdbcRunBound bound,
                            int chunkSize, Mode mode) {
    List<String> keys = keyColumns(watermarkColumn, pkColumns, mode);
    StringBuilder sb = new StringBuilder("SELECT * FROM ")
        .append(dialect.quoteIdentifier(schema)).append(".").append(dialect.quoteIdentifier(table));

    List<String> quoted = keys.stream().map(dialect::quoteIdentifier).toList();
    List<String> clauses = new ArrayList<>(2);
    List<Integer> cursorPlan = List.of();
    List<Integer> ceilingPlan = List.of();
    if (cursor != null && !cursor.pk().isEmpty()) {
      KeyComparison after = dialect.keyComparison(quoted, Comparison.GREATER_THAN).validateFor(quoted.size());
      clauses.add(after.sql());
      cursorPlan = after.parameterIndexes();
    }
    // The ceiling, as a second lexicographic row comparison over the SAME key list. In SQL, in the
    // source's own types: the one comparison the JVM must not attempt (see JdbcRunBound).
    //
    // `OR <watermark> IS NULL` is not defensive noise — it is what keeps the ceiling from CHANGING
    // what a run reads. A row-wise comparison with a NULL left operand evaluates to NULL, so a row
    // with no watermark fails `<=`. Before the ceiling, a cursorless first run emitted no WHERE at
    // all and Postgres sorts NULL LAST on an ASC order, so those rows WERE read by that first pass;
    // adding `<=` alone dropped them, and since every later run carries a cursor whose `>` excludes
    // them as well, they would never have been read by any run — measured at 10 rows out of 12,
    // `Completed`, no dead letter, no log line. With the OR, the ceiling bounds exactly what it
    // was meant to bound and touches nothing else: a first pass reads them, a resume does not,
    // both exactly as before.
    //
    // FULL mode needs none: its keys are primary-key columns, which cannot be null.
    if (bound != null) {
      KeyComparison upTo = dialect.keyComparison(quoted, Comparison.LESS_OR_EQUAL).validateFor(quoted.size());
      String ceiling = upTo.sql();
      ceilingPlan = upTo.parameterIndexes();
      if (mode == Mode.WATERMARK && watermarkColumn != null) {
        ceiling = "(" + ceiling + " OR " + dialect.quoteIdentifier(watermarkColumn) + " IS NULL)";
      }
      clauses.add(ceiling);
    }
    if (!clauses.isEmpty()) {
      sb.append(" WHERE ").append(String.join(" AND ", clauses));
    }

    sb.append(" ORDER BY ")
      .append(quoted.stream().map(q -> q + " ASC").collect(Collectors.joining(", ")));
    sb.append(" ").append(dialect.limitClause(chunkSize));
    return new Select(sb.toString(), cursorPlan, ceilingPlan);
  }

  /**
   * The query that measures the ceiling: the greatest key row the table holds right now.
   *
   * <p>{@code IS NOT NULL} on the watermark is not defensive noise. Postgres orders NULLs FIRST on a
   * DESC sort, so without it the greatest key row of a table with a NULL watermark would BE that
   * NULL, every {@code (keys) <= (NULL, …)} would be unknown, and the run would read nothing at all.
   * Those rows are not thereby excluded from the READ: {@link #buildSelect} keeps them with an
   * {@code OR <watermark> IS NULL} on the ceiling clause, because a cursorless first pass has always
   * read them. What this query measures is the ceiling of the rows that HAVE a watermark, which is
   * the only thing a ceiling can be about. Primary-key columns cannot be null, so FULL mode needs no
   * such clause.
   */
  public String buildCeilingProbe(String schema, String table, String watermarkColumn,
                                  List<String> pkColumns, Mode mode) {
    List<String> keys = keyColumns(watermarkColumn, pkColumns, mode);
    List<String> quoted = keys.stream().map(dialect::quoteIdentifier).toList();
    StringBuilder sb = new StringBuilder("SELECT ")
        .append(String.join(", ", quoted))
        .append(" FROM ").append(dialect.quoteIdentifier(schema))
        .append(".").append(dialect.quoteIdentifier(table));
    if (mode == Mode.WATERMARK && watermarkColumn != null) {
      sb.append(" WHERE ").append(dialect.quoteIdentifier(watermarkColumn)).append(" IS NOT NULL");
    }
    sb.append(" ORDER BY ")
      .append(quoted.stream().map(q -> q + " DESC").collect(Collectors.joining(", ")));
    sb.append(" ").append(dialect.limitClause(1));
    return sb.toString();
  }
}
