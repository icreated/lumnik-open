package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.jdbc.dialect.JdbcDialect;
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

  private static final List<String> DEFAULT_SKIP = List.of(
      "pg_*", "information_schema*", "t_*");

  public List<TableDescriptor> discover(JdbcConnectionConfig cc, String schema,
                                        List<String> skipPatterns, boolean includeViews) {
    JdbcDialect dialect = dialectRegistry.resolve(cc.dialectId());
    DataSource ds = poolRegistry.acquire(cc.url(), cc.user(), cc.password(), -1L);
    List<Pattern> skip = compilePatterns(skipPatterns != null ? skipPatterns : DEFAULT_SKIP);

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
    DataSource ds = poolRegistry.acquire(cc.url(), cc.user(), cc.password(), -1L);
    try (Connection conn = ds.getConnection()) {
      return describeOne(conn, dialect, schema, table);
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
    return new TableDescriptor(schema, table, pk, cols, fks, wm, rowEstimate);
  }

  private List<Pattern> compilePatterns(List<String> globs) {
    List<Pattern> out = new ArrayList<>(globs.size());
    for (String g : globs) {
      String regex = "^" + g.replace(".", "\\.").replace("*", ".*").replace("?", ".") + "$";
      out.add(Pattern.compile(regex));
    }
    return out;
  }

  private boolean matchesAny(String name, List<Pattern> patterns) {
    for (Pattern p : patterns) if (p.matcher(name).matches()) return true;
    return false;
  }
}
