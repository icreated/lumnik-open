package io.lumnik.jdbc.spi;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Dialect-specific JDBC operations of one source database. A provider module implements this and
 * is selected per connector by {@code dialect:} or, when absent, by the URL it accepts.
 *
 * <p>{@link #keyComparison} and {@link #limitClause} have no default on purpose: a default
 * (row-value tuple, {@code LIMIT n}) would let a provider compile while emitting SQL its database
 * rejects or reads differently — the silent row loss the key paging must never have. Every provider
 * states both.
 */
public interface JdbcDialect {

  /** Stable id used in registry lookup and in connector config (lowercase). */
  String id();

  /** Other names the {@code dialect:} field may carry for this provider. Default: none. */
  default Set<String> aliases() { return Set.of(); }

  /** Whether this provider recognises the JDBC URL (its scheme). */
  boolean acceptsUrl(String url);

  /** The JDBC driver class this provider owns; the pool loads it explicitly. */
  String driverClass();

  /** Quote an identifier for safe SQL composition. */
  String quoteIdentifier(String identifier);

  /**
   * The comparison of the (already quoted) key columns against the cursor or the run ceiling, with
   * its bind plan. See {@link KeyComparison}. The SQL is joined to other clauses with a bare
   * {@code AND}: an expansion containing {@code OR} must carry its own outer parentheses.
   */
  KeyComparison keyComparison(List<String> quotedColumns, Comparison comparison);

  /** The row-limit clause appended after {@code ORDER BY}, e.g. {@code LIMIT 100}. */
  String limitClause(int limit);

  /**
   * A statement run once on every new connection of this dialect's pool, before it is used — or empty.
   * For a database whose session settings can change what a query means (a sort order or number format
   * taken from the client's locale), so that keyset paging compares in the same order it sorts in.
   * Default: none.
   */
  default Optional<String> sessionInitSql() {
    return Optional.empty();
  }

  /**
   * The value of column {@code index} (1-based) of the current row, as the reader hands it to the
   * writer. A value hook, not SQL — so unlike {@link #keyComparison} a default is safe: plain
   * {@code getObject}. A provider overrides it for types whose {@code getObject} is not a plain Java
   * value (a LOB locator, a driver-specific timestamp class), and may refuse a type it cannot read by
   * throwing {@link SQLException} (it is wrapped with the table's name by the reader).
   */
  default Object readColumn(ResultSet rs, int index) throws SQLException {
    return rs.getObject(index);
  }

  /**
   * Whether column {@code index} holds a wall-clock time — a date and time the source wrote with no
   * time zone. The driver reads it in the hub JVM's zone, so the hub must not take it for an instant
   * (#1093). Default: JDBC's own reading, {@code Types.TIMESTAMP} being the type without a zone. A
   * provider whose driver reports a zoned type with that same code overrides it — PostgreSQL's does.
   */
  default boolean isWallClock(ResultSetMetaData md, int index) throws SQLException {
    return md.getColumnType(index) == Types.TIMESTAMP;
  }

  /**
   * Why this provider cannot read {@code column}, or empty when it can. Discovery lists the table but
   * names every column answered here, and the guided add refuses a table that has one — so a column the
   * provider cannot read never reaches ingestion. Default: every column is readable.
   */
  default Optional<String> unsupportedReason(ColumnDescriptor column) {
    return Optional.empty();
  }

  /** Table-name globs excluded from discovery unless the request supplies its own patterns. */
  List<String> defaultSkipPatterns();

  /** List user-visible tables in a schema, excluding system tables. */
  List<String> listTables(Connection conn, String schema) throws SQLException;

  /** Whether {@code schema.table} is a base table — one catalog lookup by name, no listing. */
  boolean tableExists(Connection conn, String schema, String table) throws SQLException;

  /** Describe columns for a table (name, jdbc type, nullable). */
  List<ColumnDescriptor> listColumns(Connection conn, String schema, String table) throws SQLException;

  /** Describe foreign keys for a table. */
  List<FkDescriptor> listForeignKeys(Connection conn, String schema, String table) throws SQLException;

  /** Primary key columns of a table, in ordinal order. */
  List<String> listPrimaryKey(Connection conn, String schema, String table) throws SQLException;

  /** Estimated row count (Postgres uses pg_class.reltuples). */
  long estimateRowCount(Connection conn, String schema, String table) throws SQLException;

  /**
   * Pick a watermark column heuristically. Order: updated_at, updated, modified_at,
   * last_modified, then any timestamp-typed column, then numeric PK fallback.
   */
  Optional<String> inferWatermarkColumn(List<ColumnDescriptor> columns, List<String> pk);
}
