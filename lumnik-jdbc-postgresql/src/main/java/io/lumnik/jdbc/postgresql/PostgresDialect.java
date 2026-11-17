package io.lumnik.jdbc.postgresql;

import io.lumnik.jdbc.spi.ColumnDescriptor;
import io.lumnik.jdbc.spi.Comparison;
import io.lumnik.jdbc.spi.FkDescriptor;
import io.lumnik.jdbc.spi.JdbcDialect;
import io.lumnik.jdbc.spi.KeyComparison;
import jakarta.enterprise.context.ApplicationScoped;
import org.postgresql.Driver;
import org.postgresql.PGProperty;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;

@ApplicationScoped
public class PostgresDialect implements JdbcDialect {

  private static final List<String> WATERMARK_PREFERENCES = List.of(
      "updated_at", "updated", "modified_at", "last_modified");

  private static final Set<String> TIMESTAMP_TYPES = Set.of(
      "TIMESTAMP", "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE", "DATE");

  private static final Set<String> NUMERIC_TYPES = Set.of(
      "BIGINT", "INTEGER", "INT", "INT4", "INT8", "SMALLINT", "NUMERIC", "DECIMAL");

  @Override
  public String id() { return "postgres"; }

  @Override
  public Set<String> aliases() { return Set.of("postgresql"); }

  @Override
  public boolean acceptsUrl(String url) { return url != null && url.startsWith("jdbc:postgresql:"); }

  @Override
  public String driverClass() { return "org.postgresql.Driver"; }

  /**
   * Read by pgjdbc itself ({@link Driver#parseURL}), so the hosts judged are the ones it connects to: several in a
   * failover URL, {@code localhost} when the URL names none. A {@code socketFactory} of the URL's choice could connect
   * anywhere the hosts do not say, and a {@code service} is resolved from the hub's own files: both are refused.
   */
  @Override
  public List<String> connectHosts(String url) {
    // A service is read from the hub's own pg_service.conf — its host, its settings: the hub's files, not the URL
    // (#2000). Read off the URL itself: the driver resolves it while parsing and drops the name.
    if (url != null && url.indexOf('?') >= 0
        && Arrays.stream(url.substring(url.indexOf('?') + 1).split("&"))
            .anyMatch(pair -> pair.split("=", 2)[0].equals(PGProperty.SERVICE.getName()))) {
      throw new IllegalArgumentException("it names a service, which the hub's own files would resolve");
    }
    Properties read;
    try {
      // A password in the defaults: without one, the parser reads the hub's ~/.pgpass, only to find the hosts.
      Properties defaults = new Properties();
      defaults.setProperty(PGProperty.PASSWORD.getName(), "");
      read = Driver.parseURL(url, defaults);
    } catch (RuntimeException driversOwn) {   // its message may quote the URL: a reason of ours instead
      throw new IllegalArgumentException("the driver does not read it");
    }
    if (read == null) throw new IllegalArgumentException("the driver does not read it");
    if (read.getProperty(PGProperty.SOCKET_FACTORY.getName()) != null) {
      throw new IllegalArgumentException("it names its own socketFactory");
    }

    String hosts = read.getProperty(PGProperty.PG_HOST.getName());
    if (hosts == null || hosts.isBlank()) throw new IllegalArgumentException("it names no host");
    return List.of(hosts.split(","));
  }

  /** pgjdbc's own properties, in seconds: {@code connectTimeout}, {@code loginTimeout}, {@code socketTimeout}. */
  @Override
  public Map<String, String> timeoutProperties(Duration connect, Optional<Duration> read) {
    Map<String, String> p = new HashMap<>();
    p.put(PGProperty.CONNECT_TIMEOUT.getName(), String.valueOf(connect.toSeconds()));
    p.put(PGProperty.LOGIN_TIMEOUT.getName(), String.valueOf(connect.toSeconds()));
    read.ifPresent(r -> p.put(PGProperty.SOCKET_TIMEOUT.getName(), String.valueOf(r.toSeconds())));
    return p;
  }

  @Override
  public List<String> defaultSkipPatterns() {
    return List.of("pg_*", "information_schema*", "t_*");
  }

  @Override
  public KeyComparison keyComparison(List<String> quotedColumns, Comparison comparison) {
    String op = comparison == Comparison.GREATER_THAN ? ">" : "<=";
    String marks = String.join(", ", Collections.nCopies(quotedColumns.size(), "?"));
    return new KeyComparison(
        "(" + String.join(", ", quotedColumns) + ") " + op + " (" + marks + ")",
        IntStream.range(0, quotedColumns.size()).boxed().toList());
  }

  @Override
  public String limitClause(int limit) { return "LIMIT " + limit; }

  @Override
  public String quoteIdentifier(String identifier) {
    if (identifier == null || identifier.isEmpty())
      throw new IllegalArgumentException("identifier must not be null or blank");
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  @Override
  public Optional<String> inferWatermarkColumn(List<ColumnDescriptor> columns, List<String> pk) {
    Map<String, ColumnDescriptor> byName = new LinkedHashMap<>();
    for (ColumnDescriptor c : columns) byName.put(c.name().toLowerCase(Locale.ROOT), c);

    for (String pref : WATERMARK_PREFERENCES) {
      if (byName.containsKey(pref)) return Optional.of(byName.get(pref).name());
    }
    for (ColumnDescriptor c : columns) {
      if (TIMESTAMP_TYPES.contains(c.jdbcType().toUpperCase(Locale.ROOT))) {
        return Optional.of(c.name());
      }
    }
    if (pk.size() == 1) {
      ColumnDescriptor pkCol = byName.get(pk.get(0).toLowerCase(Locale.ROOT));
      if (pkCol != null && NUMERIC_TYPES.contains(pkCol.jdbcType().toUpperCase(Locale.ROOT))) {
        return Optional.of(pkCol.name());
      }
    }
    return Optional.empty();
  }

  @Override
  public List<String> listTables(Connection conn, String schema) throws SQLException {
    return listRelations(conn, schema, "BASE TABLE");
  }

  @Override
  public List<String> listViews(Connection conn, String schema) throws SQLException {
    return listRelations(conn, schema, "VIEW");
  }

  private static List<String> listRelations(Connection conn, String schema, String tableType) throws SQLException {
    String sql = "SELECT table_name FROM information_schema.tables " +
                 "WHERE table_schema = ? AND table_type = ? " +
                 "ORDER BY table_name";
    List<String> out = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, schema);
      ps.setString(2, tableType);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.add(rs.getString(1));
      }
    }
    return out;
  }

  @Override
  public List<ColumnDescriptor> listColumns(Connection conn, String schema, String table)
      throws SQLException {
    String sql = "SELECT column_name, data_type, " +
                 "       COALESCE(character_maximum_length, numeric_precision, 0) AS size, " +
                 "       is_nullable " +
                 "FROM information_schema.columns " +
                 "WHERE table_schema = ? AND table_name = ? " +
                 "ORDER BY ordinal_position";
    List<ColumnDescriptor> out = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(new ColumnDescriptor(
              rs.getString(1),
              rs.getString(2).toUpperCase(Locale.ROOT).replace(" WITH TIME ZONE", "TZ"),
              rs.getInt(3),
              "YES".equalsIgnoreCase(rs.getString(4))));
        }
      }
    }
    return out;
  }

  @Override
  public List<FkDescriptor> listForeignKeys(Connection conn, String schema, String table)
      throws SQLException {
    List<FkDescriptor> out = new ArrayList<>();
    try (ResultSet rs = conn.getMetaData().getImportedKeys(null, schema, table)) {
      while (rs.next()) {
        out.add(new FkDescriptor(
            rs.getString("FKCOLUMN_NAME"),
            rs.getString("PKTABLE_SCHEM"),
            rs.getString("PKTABLE_NAME"),
            rs.getString("PKCOLUMN_NAME")));
      }
    }
    return out;
  }

  @Override
  public List<String> listPrimaryKey(Connection conn, String schema, String table)
      throws SQLException {
    List<String> cols = new ArrayList<>();
    try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, schema, table)) {
      List<Object[]> rows = new ArrayList<>();
      while (rs.next()) rows.add(new Object[]{rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME")});
      rows.sort((a, b) -> Short.compare((Short) a[0], (Short) b[0]));
      for (Object[] r : rows) cols.add((String) r[1]);
    }
    return cols;
  }

  @Override
  public boolean tableExists(Connection conn, String schema, String table) throws SQLException {
    String sql = "SELECT 1 FROM information_schema.tables " +
                 "WHERE table_schema = ? AND table_name = ? AND table_type = 'BASE TABLE'";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  @Override
  public long estimateRowCount(Connection conn, String schema, String table) throws SQLException {
    String sql = "SELECT COALESCE(reltuples::bigint, 0) " +
                 "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                 "WHERE n.nspname = ? AND c.relname = ?";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getLong(1);
      }
    }
    return 0L;
  }

  /**
   * pgJDBC reports {@code timestamp} and {@code timestamptz} with the same code, {@code
   * Types.TIMESTAMP} — only the type name tells the wall-clock time from the instant (#1093,
   * measured with pgJDBC 42.7.8).
   */
  @Override
  public boolean isWallClock(ResultSetMetaData md, int index) throws SQLException {
    return "timestamp".equals(md.getColumnTypeName(index));
  }
}
