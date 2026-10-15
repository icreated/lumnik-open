package io.lumnik.hub.connector.jdbc;

import io.lumnik.jdbc.spi.FkDescriptor;
import io.lumnik.jdbc.spi.ColumnDescriptor;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.jdbc.spi.JdbcDialect;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@ApplicationScoped
public class SchemaIntrospector {

  @Inject JdbcDialectRegistry dialectRegistry;
  @Inject HikariPoolRegistry poolRegistry;

  public List<TableDescriptor> discover(JdbcConnectionConfig cc, String schema,
                                        List<String> skipPatterns, boolean includeViews) {
    JdbcDialect dialect = dialectRegistry.resolve(cc.dialectId());
    DataSource ds = poolRegistry.acquire(cc.url(), cc.user(), cc.password(), -1L, dialect);
    List<Pattern> skip = compilePatterns(skipPatterns != null ? skipPatterns : dialect.defaultSkipPatterns());

    List<TableDescriptor> out = new ArrayList<>();
    try (Connection conn = ds.getConnection()) {
      for (String tableName : dialect.listTables(conn, schema)) {
        if (matchesAny(tableName, skip)) continue;
        out.add(describeOne(conn, dialect, schema, tableName));
      }
    } catch (Exception e) {
      throw new RuntimeException("Discovery failed for " + cc.url() + " / " + schema, e);
    }
    return out;
  }

  public TableDescriptor describe(JdbcConnectionConfig cc, String schema, String table) {
    JdbcDialect dialect = dialectRegistry.resolve(cc.dialectId());
    DataSource ds = poolRegistry.acquire(cc.url(), cc.user(), cc.password(), -1L, dialect);
    try (Connection conn = ds.getConnection()) {
      // JDBC metadata answers an unknown table with empty lists, not an error — ask the catalog.
      if (!dialect.tableExists(conn, schema, table)) {
        throw new ConfigValidationException("table " + schema + "." + table
            + " not found among the base tables of that schema — check the schema and the connection user's "
            + "SELECT grant; a view is declared in a manifest, not through the guided add");
      }
      TableDescriptor described = describeOne(conn, dialect, schema, table);
      if (!described.unsupportedColumns().isEmpty()) {
        throw new ConfigValidationException("table " + schema + "." + table + " cannot be ingested: "
            + String.join("; ", unsupportedReasons(dialect, described.columns())));
      }
      return described;
    } catch (ConfigValidationException refusal) {
      throw refusal;
    } catch (Exception e) {
      throw new RuntimeException("Describe failed for " + schema + "." + table, e);
    }
  }

  private TableDescriptor describeOne(Connection conn, JdbcDialect dialect,
                                      String schema, String table) throws Exception {
    List<String> pk = dialect.listPrimaryKey(conn, schema, table);
    List<ColumnDescriptor> cols = dialect.listColumns(conn, schema, table);
    List<FkDescriptor> fks = dialect.listForeignKeys(conn, schema, table);
    Optional<String> wm = dialect.inferWatermarkColumn(cols, pk);
    long rowEstimate = dialect.estimateRowCount(conn, schema, table);
    List<String> unsupported = cols.stream()
        .filter(c -> dialect.unsupportedReason(c).isPresent())
        .map(c -> c.name() + " " + c.jdbcType())
        .toList();
    return new TableDescriptor(schema, table, pk, cols, fks, wm, rowEstimate, unsupported);
  }

  private static List<String> unsupportedReasons(JdbcDialect dialect, List<ColumnDescriptor> cols) {
    return cols.stream().map(dialect::unsupportedReason).flatMap(Optional::stream).toList();
  }

  private List<Pattern> compilePatterns(List<String> globs) {
    List<Pattern> out = new ArrayList<>(globs.size());
    for (String g : globs) out.add(globToPattern(g));
    return out;
  }

  /**
   * A glob is literal text plus the two wildcards {@code *} (any run) and {@code ?} (one character).
   * Literal runs are quoted, so {@code $} in an Oracle recycle-bin glob ({@code BIN$*}) is a dollar sign,
   * not a regex end anchor that would make the pattern match nothing.
   */
  static Pattern globToPattern(String glob) {
    StringBuilder regex = new StringBuilder("^");
    StringBuilder literal = new StringBuilder();
    for (char c : glob.toCharArray()) {
      if (c == '*' || c == '?') {
        if (literal.length() > 0) {
          regex.append(Pattern.quote(literal.toString()));
          literal.setLength(0);
        }
        regex.append(c == '*' ? ".*" : ".");
      } else {
        literal.append(c);
      }
    }
    if (literal.length() > 0) regex.append(Pattern.quote(literal.toString()));
    return Pattern.compile(regex.append('$').toString());
  }

  private boolean matchesAny(String name, List<Pattern> patterns) {
    for (Pattern p : patterns) if (p.matcher(name).matches()) return true;
    return false;
  }
}
