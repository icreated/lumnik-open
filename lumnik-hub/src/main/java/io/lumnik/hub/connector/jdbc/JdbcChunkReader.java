package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialect;
import io.lumnik.hub.connector.mapping.writer.SchemaInferrer;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Executes paginated JDBC reads against an external source database.
 *
 * <p>Two modes:
 * <ul>
 *   <li>WATERMARK — reads rows changed since the last cursor (watermark column + PK tiebreak)</li>
 *   <li>FULL — reads all rows ordered by PK; cursor is PK-only, enabling chunked full scans</li>
 * </ul>
 *
 * <p>The watermark value is stored as a String in the cursor so that Jackson round-trips
 * through JSON remain type-safe (avoids Timestamp→Long coercion in ObjectMapper.convertValue).
 */
public class JdbcChunkReader implements ItemReader<Map<String, Object>> {

  public enum Mode { WATERMARK, FULL }

  /**
   * Key under which this reader stashes {@code Map<columnLabel, pgType>} (the source's declared
   * column types) on the shared {@link ConnectorContext} config, so the writer can authoritatively
   * type columns whose sampled values were all NULL. See {@code JdbcConnectorType.writer}.
   */
  public static final String SOURCE_COLUMN_TYPES_KEY = "_sourceColumnTypes";

  private final String url;
  private final String user;
  private final String password;
  private final String schema;
  private final String table;
  private final String watermarkColumn;
  private final List<String> pkColumns;
  private final Mode mode;
  private final HikariPoolRegistry poolRegistry;
  private final JdbcQueryBuilder qb;

  public JdbcChunkReader(String url, String user, String password, String dialectId,
                         String schema, String table, String watermarkColumn,
                         List<String> pkColumns, Mode mode,
                         JdbcDialect dialect, HikariPoolRegistry poolRegistry) {
    this.url = url;
    this.user = user;
    this.password = password;
    this.schema = schema;
    this.table = table;
    this.watermarkColumn = watermarkColumn;
    this.pkColumns = pkColumns;
    this.mode = mode;
    this.poolRegistry = poolRegistry;
    this.qb = new JdbcQueryBuilder(dialect);
  }

  @Override
  public ReadResult<Map<String, Object>> read(int chunkSize, ConnectorCursor cursor,
                                              ConnectorContext ctx) {
    long tenantId = ctx != null ? ctx.tenantId() : -1L;
    DataSource ds = poolRegistry.acquire(url, user, password, tenantId);
    JdbcCursor jc = JdbcCursor.fromConnectorCursor(cursor);
    JdbcQueryBuilder.Mode qMode = (mode == Mode.FULL)
        ? JdbcQueryBuilder.Mode.FULL : JdbcQueryBuilder.Mode.WATERMARK;

    String sql = qb.buildSelect(schema, table, watermarkColumn, pkColumns, jc, chunkSize, qMode);

    try (Connection conn = ds.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {

      bindCursorParams(ps, jc, qMode);

      try (ResultSet rs = ps.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        captureSourceColumnTypes(md, colCount, ctx);
        Map<String, Object> lastRow = null;

        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= colCount; i++) {
            row.put(md.getColumnLabel(i), rs.getObject(i));
          }
          rows.add(row);
          lastRow = row;
        }

        if (rows.size() < chunkSize) {
          return new ReadResult.EndOfStream<>(rows);
        }

        JdbcCursor next = buildNextCursor(lastRow, qMode);
        return new ReadResult.HasMore<>(rows, next.toConnectorCursor());
      }

    } catch (SQLException e) {
      throw new RuntimeException("JDBC read failed on " + schema + "." + table + ": " + e.getMessage(), e);
    }
  }

  /**
   * Records the source's declared column types (label → PostgreSQL type) on the shared context so
   * the writer can type all-NULL-sampled columns authoritatively. Best-effort: skipped when there
   * is no context or its config map is immutable (e.g. unit tests).
   */
  private static void captureSourceColumnTypes(ResultSetMetaData md, int colCount, ConnectorContext ctx)
      throws SQLException {
    if (ctx == null || ctx.config() == null) return;
    Map<String, String> colTypes = new LinkedHashMap<>();
    for (int i = 1; i <= colCount; i++) {
      colTypes.put(md.getColumnLabel(i), SchemaInferrer.pgTypeForSqlType(md.getColumnType(i)));
    }
    try {
      ctx.config().put(SOURCE_COLUMN_TYPES_KEY, colTypes);
    } catch (UnsupportedOperationException ignored) {
      // Immutable config (tests) — hints are an optimization; value inference remains the fallback.
    }
  }

  /**
   * Bind the cursor parameters into the PreparedStatement.
   *
   * <p>WATERMARK with cursor: 3 params — (wm > ?) OR (wm = ? AND pk > ?)
   * <p>FULL with cursor:      1 param  — pk > ?
   *
   * <p>The watermark value is stored as a String (from Timestamp.toString()) to survive
   * Jackson JSON round-trips. We parse it back to a {@link Timestamp} before binding so
   * that PostgreSQL can compare it against a TIMESTAMP column without an explicit cast.
   */
  private void bindCursorParams(PreparedStatement ps, JdbcCursor jc, JdbcQueryBuilder.Mode m)
      throws SQLException {
    boolean hasCursor = jc != null && !jc.pk().isEmpty();
    if (!hasCursor) return;

    Object pkVal = jc.pk().get(pkColumns.get(0));

    if (m == JdbcQueryBuilder.Mode.WATERMARK) {
      Timestamp wmTs = toTimestamp(jc.value());
      if (wmTs != null) {
        ps.setTimestamp(1, wmTs);
        ps.setTimestamp(2, wmTs);
      } else {
        ps.setObject(1, jc.value());
        ps.setObject(2, jc.value());
      }
      ps.setObject(3, pkVal);
    } else {
      ps.setObject(1, pkVal);
    }
  }

  /**
   * Convert a cursor watermark value to {@link Timestamp} for JDBC binding.
   *
   * <p>The value may be:
   * <ul>
   *   <li>A String from Timestamp.toString() — format {@code "yyyy-MM-dd HH:mm:ss.n"}</li>
   *   <li>A String from a numeric watermark — returned as-is (null from this method)</li>
   *   <li>Already a Timestamp — returned as-is</li>
   * </ul>
   */
  private static Timestamp toTimestamp(Object value) {
    if (value == null) return null;
    if (value instanceof Timestamp ts) return ts;
    if (value instanceof String s) {
      try {
        return Timestamp.valueOf(LocalDateTime.parse(s, TIMESTAMP_FORMATTER));
      } catch (Exception ignored) {
        // Not a timestamp string — fall through
      }
      try {
        // Fallback: Timestamp.valueOf accepts "yyyy-MM-dd HH:mm:ss[.nanos]"
        return Timestamp.valueOf(s);
      } catch (Exception ignored) {
        return null;
      }
    }
    return null;
  }

  private static final DateTimeFormatter TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSSSSS][.SSSSSS][.SSS][.S]");

  /**
   * Build the next cursor from the last row read.
   *
   * <p>The watermark value is normalized to String via {@code Object.toString()} so that
   * Jackson's ObjectMapper round-trip through JSON does not coerce it to Long. The PostgreSQL
   * JDBC driver accepts a String for TIMESTAMP parameters via {@code setObject}.
   */
  private JdbcCursor buildNextCursor(Map<String, Object> lastRow, JdbcQueryBuilder.Mode m) {
    if (lastRow == null) {
        return new JdbcCursor("", null, Map.of());
    }
    Object rawWm = (m == JdbcQueryBuilder.Mode.WATERMARK && watermarkColumn != null)
        ? lastRow.get(watermarkColumn) : null;

    // Normalize to String so Jackson round-trips are stable (Timestamp → String, not Long)
    Object wmValue = (rawWm != null) ? rawWm.toString() : null;

    Map<String, Object> pkMap = new LinkedHashMap<>();
    pkMap.put(pkColumns.get(0), lastRow.get(pkColumns.get(0)));

    String col = (watermarkColumn != null) ? watermarkColumn : "";
    return new JdbcCursor(col, wmValue, pkMap);
  }
}
