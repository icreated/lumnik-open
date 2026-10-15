package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.api.ItemReader;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.ConnectorCursor;
import io.lumnik.hub.connector.api.types.ReadResult;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.jdbc.spi.JdbcDialect;
import io.lumnik.hub.connector.mapping.writer.SchemaInferrer;

import javax.sql.DataSource;
import org.jboss.logging.Logger;
import java.sql.*;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Executes paginated JDBC reads against an external source database.
 *
 * <p>Two modes:
 * <ul>
 *   <li>WATERMARK — reads rows changed since the last cursor (watermark column, with the whole
 *       primary key as tiebreak)</li>
 *   <li>FULL — reads all rows ordered by the whole primary key, enabling chunked full scans</li>
 * </ul>
 *
 * <p>The watermark value is stored as a String in the cursor so that Jackson round-trips
 * through JSON remain type-safe (avoids Timestamp→Long coercion in ObjectMapper.convertValue).
 */
public class JdbcChunkReader implements ItemReader<Map<String, Object>> {

  public enum Mode {
    WATERMARK, FULL;

    /**
     * The query builder's mode for this one. ONE owner: the reader and
     * {@code JdbcConnectorType.runStart} both need it, and the whole risk the ceiling introduces is
     * a probe measuring a key layout the reader does not page on — so the mapping that decides that
     * layout is not a thing to write twice.
     */
    public JdbcQueryBuilder.Mode forQuery() {
      return this == FULL ? JdbcQueryBuilder.Mode.FULL : JdbcQueryBuilder.Mode.WATERMARK;
    }
  }

  private static final Logger LOG = Logger.getLogger(JdbcChunkReader.class);

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
  private final JdbcDialect dialect;
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
    this.dialect = dialect;
    this.poolRegistry = poolRegistry;
    this.qb = new JdbcQueryBuilder(dialect);
  }

  @Override
  public ReadResult<Map<String, Object>> read(int chunkSize, ConnectorCursor cursor,
                                              ConnectorContext ctx) {
    // A watermark reader with no watermark column cannot honour its mode: paging on the primary
    // key alone would silently make it a full scan, run after run. Refuse on the first chunk —
    // the declarative validator refuses it at apply time (JdbcConnectorValidator.validateTable), so
    // only a row that predates that check gets here. NOT validateConfig: nothing on the jdbc path
    // calls it.
    if (mode == Mode.WATERMARK && watermarkColumn == null) {
      throw new ConfigValidationException(schema + "." + table
          + ": mode 'watermark' declares no watermark_column — the reader cannot page on a column"
          + " it does not have; set watermark_column, or switch the table to mode 'full'");
    }
    long tenantId = ctx != null ? ctx.tenantId() : -1L;
    DataSource ds = poolRegistry.acquire(url, user, password, tenantId, dialect);
    JdbcCursor jc = JdbcCursor.fromConnectorCursor(cursor);
    // A re-applied manifest that changes mode, watermark_column or pk_columns meets the cursor the
    // previous configuration persisted (apply upserts the endpoint, keeps the cursor row). Honouring
    // it binds NULL or resumes mid-table — silently. A cursor this reader would not itself write
    // (see buildNextCursor) is ignored: the table restarts. See JdbcChunkReaderModeSwitchIT.
    String mine = cursorColumn();
    if (!jc.pk().isEmpty() && !(mine.equals(jc.col()) && List.copyOf(jc.pk().keySet()).equals(pkColumns))) {
      LOG.warnf("%s.%s: ignoring cursor persisted by another configuration (col='%s', pk=%s; this reader writes col='%s', pk=%s) — restarting from the beginning",
          schema, table, jc.col(), jc.pk().keySet(), mine, pkColumns);
      jc = JdbcCursor.empty();
    }
    JdbcQueryBuilder.Mode qMode = mode.forQuery();

    // The run's target, measured once by JdbcConnectorType.runStart and carried on the context.
    // Both sides derive their key layout from Mode.forQuery() + JdbcQueryBuilder.keyColumns, so
    // there is nothing here to reconcile. The first version compared the two layouts and fell back
    // to NO bound when they differed — a guard for a state that cannot occur, whose failure mode
    // was a silent return to the endless run this ceiling exists to end.
    JdbcRunBound bound = ctx != null && ctx.bound() instanceof JdbcRunBound b ? b : null;

    JdbcQueryBuilder.Select select = qb.buildSelect(schema, table, watermarkColumn, pkColumns, jc, bound, chunkSize, qMode);
    String sql = select.sql();

    try (Connection conn = ds.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {

      // The whole chunk in one round trip. Left unset, the batch is the driver's: ojdbc 23 read a 500-row
      // chunk in 5 round trips. At the chunk size it took 2, the second only to learn there was no more; one
      // row past the limit, the first fetch comes back short and that says it. pgjdbc honours a fetch size
      // only inside a transaction (it then reads through a server-side cursor); this pool's connections are
      // autocommit, so PostgreSQL still sends the whole result at once, as before.
      ps.setFetchSize(chunkSize + 1);
      int next = bind(ps, select.cursorPlan(), cursorKeyValues(jc, qMode), 1);
      bind(ps, select.ceilingPlan(), bound == null ? List.of() : bound.values(), next);

      try (ResultSet rs = ps.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        captureSourceColumnTypes(md, colCount, ctx);

        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= colCount; i++) {
            row.put(md.getColumnLabel(i), dialect.readColumn(rs, i));
          }
          rows.add(row);
        }

        // Every non-empty chunk is HasMore and the stream ends on the read that finds nothing:
        // the engine persists a position only from HasMore (see ReadResult.EndOfStream), so a
        // loaded EndOfStream would forfeit its tail. Costs one 0-row keyset query per run.
        if (rows.isEmpty()) {
          return new ReadResult.EndOfStream<>(rows);
        }
        return new ReadResult.HasMore<>(rows, buildNextCursor(rows.getLast()).toConnectorCursor());
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
   * The cursor's value for each key column, in the very order {@link JdbcQueryBuilder#keyColumns} gave
   * the SELECT — so a change to the key layout moves the plan's targets and their values together.
   * Empty when the cursor carries nothing (a first read).
   *
   * <p>The watermark value is stored as a String (from Timestamp.toString()) to survive
   * Jackson JSON round-trips. We parse it back to a {@link Timestamp} so that PostgreSQL can compare it
   * against a TIMESTAMP column without an explicit cast.
   */
  private List<Object> cursorKeyValues(JdbcCursor jc, JdbcQueryBuilder.Mode m) {
    if (jc == null || jc.pk().isEmpty()) return List.of();

    List<String> keys = JdbcQueryBuilder.keyColumns(watermarkColumn, pkColumns, m);
    List<Object> values = new ArrayList<>(keys.size());
    for (int i = 0; i < keys.size(); i++) {
      // keyColumns puts the watermark first in WATERMARK mode; every other key is a pk column,
      // read by name so it cannot drift with the order the store returned the cursor in.
      if (i == 0 && m == JdbcQueryBuilder.Mode.WATERMARK) {
        Timestamp wmTs = toTimestamp(jc.value());
        values.add(wmTs != null ? wmTs : jc.value());
      } else {
        values.add(jc.pk().get(keys.get(i)));
      }
    }
    return values;
  }

  /**
   * Bind one value per {@code ?} of a comparison: placeholder {@code i} takes
   * {@code keyValues[plan[i]]}, starting at JDBC index {@code firstIndex}. Returns the index the next
   * clause starts at — the count the previous binder RETURNS is what places the ceiling, not a
   * re-derivation of "did the cursor emit a clause?", which is the same question answered twice and
   * the way the two drift apart. A dialect that expands a tuple repeats an index in its plan, and the
   * value is bound once per occurrence.
   *
   * <p>A {@link Timestamp} goes through {@code setTimestamp}; everything else through
   * {@code setObject} with the driver's own value — the ceiling's values never went through jsonb, so
   * there is nothing to parse back (that is the point of measuring the ceiling per run and never
   * persisting it).
   */
  static int bind(PreparedStatement ps, List<Integer> plan, List<Object> keyValues, int firstIndex)
      throws SQLException {
    for (int i = 0; i < plan.size(); i++) {
      Object v = keyValues.get(plan.get(i));
      if (v instanceof Timestamp ts) {
        ps.setTimestamp(firstIndex + i, ts);
      } else {
        ps.setObject(firstIndex + i, v);
      }
    }
    return firstIndex + plan.size();
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
  private JdbcCursor buildNextCursor(Map<String, Object> lastRow) {
    String col = cursorColumn();
    Object rawWm = col.isEmpty() ? null : lastRow.get(col);

    // Normalize to String so Jackson round-trips are stable (Timestamp → String, not Long)
    Object wmValue = (rawWm != null) ? rawWm.toString() : null;

    // Every primary-key column, in declaration order: a cursor holding only the first one resumes
    // at the next distinct value of that column and skips the rest of the group it stopped inside.
    Map<String, Object> pkMap = new LinkedHashMap<>();
    for (String pkCol : pkColumns) {
      pkMap.put(pkCol, lastRow.get(pkCol));
    }

    return new JdbcCursor(col, wmValue, pkMap);
  }

  /** The {@code col} this reader stamps on the cursors it writes: the watermark column, or {@code ""} for a full scan. */
  private String cursorColumn() {
    return (mode == Mode.WATERMARK && watermarkColumn != null) ? watermarkColumn : "";
  }
}
