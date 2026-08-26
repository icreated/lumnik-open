package io.lumnik.hub.connector.jdbc.dialect;

import io.lumnik.hub.connector.jdbc.ColumnDescriptor;
import io.lumnik.hub.connector.jdbc.FkDescriptor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Dialect-specific JDBC operations. v1 ships one implementation: PostgresDialect. */
public interface JdbcDialect {

  /** Stable id used in JdbcDialectRegistry lookup and in connector config. */
  String id();

  /** List user-visible tables in a schema, excluding system tables. */
  List<String> listTables(Connection conn, String schema) throws SQLException;

  /** Describe columns for a table (name, jdbc type, nullable). */
  List<ColumnDescriptor> listColumns(Connection conn, String schema, String table) throws SQLException;

  /** Describe foreign keys for a table. */
  List<FkDescriptor> listForeignKeys(Connection conn, String schema, String table) throws SQLException;

  /** Primary key columns of a table, in ordinal order. */
  List<String> listPrimaryKey(Connection conn, String schema, String table) throws SQLException;

  /** Estimated row count (Postgres uses pg_class.reltuples). */
  long estimateRowCount(Connection conn, String schema, String table) throws SQLException;

  /** Quote an identifier for safe SQL composition. */
  String quoteIdentifier(String identifier);

  /**
   * Pick a watermark column heuristically. Order: updated_at, updated, modified_at,
   * last_modified, then any timestamp-typed column, then numeric PK fallback.
   */
  Optional<String> inferWatermarkColumn(List<ColumnDescriptor> columns, List<String> pk);
}
