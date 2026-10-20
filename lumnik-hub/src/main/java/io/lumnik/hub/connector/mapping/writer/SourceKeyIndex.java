package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.search.mark.SearchProjectionRunHook;
import io.lumnik.hub.sql.SqlIdentifiers;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import static io.lumnik.hub.connector.mapping.writer.DynamicTableWriter.exec;
import static io.lumnik.hub.connector.mapping.writer.DynamicTableWriter.fqn;
import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * The unique index that makes a keyed table hold one row per source key (#1196), and the one-time move
 * of a table written before it, which held one row per VERSION.
 *
 * <p>The index is partial, over one tenant's rows of one source, one index per tenant: a table can
 * hold another source's rows (a sharing older than #1199) and another tenant's (a connector of the same
 * name, reading another database, keyed its own way), and neither is moved, constrained or counted by
 * this one's key. {@code NULLS NOT DISTINCT}: a key with a NULL is still one key, as the upsert has to
 * find it again.
 *
 * <p>The move runs under the table's lock ({@link DynamicTableWriter#firstWriteLock}), in one
 * transaction: per key it keeps the newest row — the highest {@code id}, the row #1185's entity view
 * already reads — deletes the others and creates the index. A key one run read twice with two values
 * is not a key: then nothing is removed and the run fails.
 */
@ApplicationScoped
public class SourceKeyIndex {

  /** PostgreSQL's identifier length, in bytes; a longer name is cut, and must be cut the same way here. */
  private static final int MAX_IDENTIFIER = 63;
  /** One tenant's rows of one source. */
  private static final String MINE = " WHERE tenant_id = ? AND _source = ?";

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Inject SearchProjectionRunHook searchMarks;

  /**
   * Puts the index in place, moving the table to one row per key first if it predates it, and returns the
   * rows the move removed. Asked of the catalog on every chunk, not remembered: a table dropped and created
   * again (a connector deleted, then applied again) has lost its index, and the upsert cannot run without
   * one. The lock is taken only when the index is missing. A table not created yet has nothing to move.
   */
  public long ensure(MappingTarget.Table spec, String source, long tenantId) {
    long removed;
    try (Connection c = adminDs.getConnection()) {
      if (spec.key().equals(indexedKey(c, spec, tenantId)) || !UnseenRowSweeper.exists(c, fqn(spec))) return 0;
      c.setAutoCommit(false);
      try {
        DynamicTableWriter.firstWriteLock(c, spec.schema(), spec.name());
        removed = spec.key().equals(indexedKey(c, spec, tenantId)) ? 0 : move(c, spec, source, tenantId);
        c.commit();
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    } catch (ConfigValidationException e) {
      throw e;
    } catch (SQLException | RuntimeException e) {
      throw new ConnectorException("SourceKeyIndex.ensure: " + e.getMessage(), e);
    }
    if (removed > 0) searchMarks.tableLostRows(tenantId, spec.name());
    return removed;
  }

  private long move(Connection c, MappingTarget.Table spec, String source, long tenantId) throws SQLException {
    refuseRepeatedKeyInTable(c, spec, source, tenantId);
    // Another key declared since the index was built: the rows are moved to the new one.
    exec(c, "DROP INDEX IF EXISTS " + quoteIdent(spec.schema()) + "." + quoteIdent(indexName(spec, tenantId)));
    long removed;
    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + fqn(spec) + " WHERE id IN (SELECT id FROM "
        + "(SELECT id, row_number() OVER (PARTITION BY " + columns(spec) + " ORDER BY id DESC) AS n "
        + "FROM " + fqn(spec) + MINE + ") v WHERE n > 1)")) {
      ps.setLong(1, tenantId);
      ps.setString(2, source);
      removed = ps.executeUpdate();
    }
    exec(c, "CREATE UNIQUE INDEX " + quoteIdent(indexName(spec, tenantId)) + " ON " + fqn(spec)
        + " (" + columns(spec) + ") NULLS NOT DISTINCT WHERE " + predicate(tenantId, source));
    return removed;
  }


  /**
   * Two different rows one run brought for one key: the source holds both, so the declared key does not
   * identify a row, and keeping the newest would delete a row the source still has. Rows older than the
   * run id (no {@code _run_id}) cannot be told apart from versions, and are not asked.
   */
  private static void refuseRepeatedKeyInTable(Connection c, MappingTarget.Table spec, String source, long tenantId)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + fqn(spec) + MINE
        + " AND _run_id IS NOT NULL GROUP BY _run_id, " + columns(spec)
        + " HAVING count(DISTINCT _row_hash) > 1 LIMIT 1")) {
      ps.setLong(1, tenantId);
      ps.setString(2, source);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) throw repeatedKey(spec, "one earlier run read two different rows with the same key", true);
      }
    }
  }

  /**
   * Two different rows of one chunk under one key (#1196): the source holds both, so the declared key does
   * not identify a row, and the upsert would keep one of them. Identical rows are one row, read twice.
   */
  static void refuseRepeatedKeyInChunk(MappingTarget.Table spec, List<Map<String, Object>> rows,
                                       List<Map<String, Object>> sys) {
    Map<List<Object>, Object> hashByKey = new HashMap<>();
    for (int i = 0; i < rows.size(); i++) {
      Map<String, Object> row = rows.get(i);
      List<Object> key = spec.key().stream().map(row::get).toList();
      Object hash = sys.get(i).get("_row_hash");
      Object seen = hashByKey.putIfAbsent(key, hash);
      if (seen != null && !seen.equals(hash)) {
        throw repeatedKey(spec, "one read brought two different rows with the same key", false);
      }
    }
  }

  /**
   * A key of this chunk that this run already wrote with other content (#1196): a watermark reader pages
   * on {@code (watermark, key)}, so the rows of a key that is not one sit in any two chunks, and the upsert
   * would keep the later without a word. A row this run rewrote unchanged (a chunk read again) has the same
   * hash and is not one. Read through this tenant's key index, the predicate written out so the planner
   * can use it.
   */
  public void refuseRepeatedKeyInRun(MappingTarget.Table spec, List<Map<String, Object>> rows,
                                     List<Map<String, Object>> sys, Map<String, String> types,
                                     long tenantId, String source, UUID runId) {
    if (runId == null) return;
    List<String> key = spec.key();
    StringBuilder values = new StringBuilder();
    for (int r = 0; r < rows.size(); r++) {
      values.append(r == 0 ? "(" : ", (");
      for (String col : key) values.append("CAST(? AS ").append(types.getOrDefault(col, "TEXT")).append("), ");
      values.append("?)");
    }
    StringBuilder on = new StringBuilder();
    for (int i = 0; i < key.size(); i++) {
      on.append(i == 0 ? "" : " AND ").append("t.").append(quoteIdent(key.get(i))).append(" IS NOT DISTINCT FROM v.c").append(i);
    }
    String columns = IntStream.range(0, key.size()).mapToObj(i -> "c" + i).collect(Collectors.joining(", "));
    String sql = "SELECT 1 FROM " + fqn(spec) + " t JOIN (VALUES " + values + ") v(" + columns + ", h) ON " + on
        + " WHERE " + predicate("t.", tenantId, source)
        + " AND t._run_id = ? AND t._row_hash <> v.h LIMIT 1";
    try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
      int i = 1;
      for (int r = 0; r < rows.size(); r++) {
        for (String col : key) ps.setObject(i++, DynamicTableWriter.bindable(rows.get(r).get(col), types.get(col)));
        ps.setString(i++, (String) sys.get(r).get("_row_hash"));
      }
      ps.setObject(i, runId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) throw repeatedKey(spec, "this run read two different rows with the same key", false);
      }
    } catch (SQLException e) {
      throw new ConnectorException("SourceKeyIndex.refuseRepeatedKeyInRun: " + e.getMessage(), e);
    }
  }

  /** The refusal for a declared key that does not identify a row; {@code moving}: the table was not changed. */
  private static ConfigValidationException repeatedKey(MappingTarget.Table spec, String evidence, boolean moving) {
    return new ConfigValidationException("pk_columns (" + String.join(", ", spec.key()) + ") does not identify "
        + "one row of " + spec.schema() + "." + spec.name() + ": " + evidence + " — declare the columns of the "
        + "source table's primary key" + (moving ? "; nothing was removed" : ""));
  }

  /** The key the index was built on, or null when there is none. */
  private static List<String> indexedKey(Connection c, MappingTarget.Table spec, long tenantId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT a.attname FROM pg_index i "
        + "JOIN pg_class x ON x.oid = i.indexrelid "
        + "CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(attnum, ord) "
        + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum "
        + "WHERE i.indrelid = to_regclass(?) AND x.relname = ? ORDER BY k.ord")) {
      ps.setString(1, fqn(spec));
      ps.setString(2, indexName(spec, tenantId));
      List<String> cols = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) cols.add(rs.getString(1));
      }
      return cols.isEmpty() ? null : cols;
    }
  }

  /**
   * {@code <table>_key_<tenant>}, within what PostgreSQL keeps of a name (63 bytes), so the lookup finds
   * what it created. A table name too long for that is cut and followed by 12 hex digits of its SHA-256:
   * two long names sharing their start must not name one index, and a Java hash code collides on purpose
   * built names. A hub table's name is a plain identifier, so a character is a byte.
   */
  static String indexName(MappingTarget.Table spec, long tenantId) {
    String suffix = "_key_" + tenantId;
    String name = spec.name() + suffix;
    if (name.length() <= MAX_IDENTIFIER) return name;
    String hash = sha256Hex(spec.name()).substring(0, 12);
    return spec.name().substring(0, MAX_IDENTIFIER - suffix.length() - hash.length() - 1) + "_" + hash + suffix;
  }

  private static String sha256Hex(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JVM ships SHA-256", e);
    }
  }

  /** The index predicate, which the upsert repeats so PostgreSQL picks this index as its arbiter. */
  static String predicate(long tenantId, String source) {
    return predicate("", tenantId, source);
  }

  /** The same, its columns read through {@code alias} ({@code "t."}), or {@code ""} for the table's own. */
  private static String predicate(String alias, long tenantId, String source) {
    return alias + "tenant_id = " + tenantId + " AND " + alias + "_source = '" + source.replace("'", "''") + "'";
  }

  static String columns(MappingTarget.Table spec) {
    return spec.key().stream().map(SqlIdentifiers::quoteIdent).collect(Collectors.joining(", "));
  }

}
