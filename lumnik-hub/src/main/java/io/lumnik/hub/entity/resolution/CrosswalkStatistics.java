package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * #989: a table that holds rows but has never been analysed is planned as if it were empty, so on a
 * fresh install the crosswalk lookups ({@code find}, {@code candidateEntityIds}) pick the wrong
 * index and cost more with every row resolved, until autovacuum's first analyse. This analyses
 * {@code entity_identity} and {@code entity_identity_key} once, as soon as they hold
 * {@link #MIN_ROWS} rows. Asked after every page by {@link EntityResolutionPass}; a table is
 * remembered once it has statistics (or once analysing it failed) and costs nothing afterwards, while
 * a table that is still small or missing is asked about again on the next page. Best effort: a
 * failure is logged once and the pass goes on.
 */
@ApplicationScoped
public class CrosswalkStatistics {

  private static final Logger LOG = Logger.getLogger(CrosswalkStatistics.class);

  /** Tables whose lookups the resolution pass hammers: the crosswalk and its fingerprints. */
  static final String SCHEMA = "core";
  static final List<String> CROSSWALK = List.of("entity_identity", "entity_identity_key");

  static final int MIN_ROWS = 1000;

  @Inject @DataSource("admin") AgroalDataSource adminDs;

  public void analyseIfNeverAnalysed() {
    analyseNeverAnalysed(SCHEMA, CROSSWALK);
  }

  /** Tables known to have statistics ({@code schema.table}): never asked about again. */
  private final Set<String> settled = ConcurrentHashMap.newKeySet();

  void analyseNeverAnalysed(String schema, List<String> tables) {
    List<String> pending = tables.stream().filter(t -> !settled.contains(schema + "." + t)).toList();
    if (pending.isEmpty()) return;
    try (Connection c = adminDs.getConnection()) {
      for (String table : pending) {
        try {
          if (analyse(c, schema, table)) settled.add(schema + "." + table);
        } catch (SQLException e) {
          // Logged once, not once per page: autovacuum will do it, this was only to be sooner.
          settled.add(schema + "." + table);
          LOG.warnf(e, "CrosswalkStatistics: could not analyse %s.%s — the planner keeps guessing until "
              + "autovacuum does", schema, table);
        }
      }
    } catch (SQLException e) {
      LOG.warnf(e, "CrosswalkStatistics: no connection to analyse %s with", pending);
    }
  }

  /**
   * True once the table has statistics: found analysed, or analysed here. One analyser at a time,
   * across threads and nodes: a session advisory lock on the table, and under it a second look at
   * {@code pg_stats} (column statistics: only ANALYZE writes them, VACUUM does not, and unlike the
   * activity view it does not lag), so a caller that waited out another's ANALYZE does not repeat it. A caller that
   * finds the lock taken leaves the table to the holder and asks again on its next page.
   */
  private static boolean analyse(Connection c, String schema, String table) throws SQLException {
    Boolean never = neverAnalysed(c, schema, table);
    if (never == null) return false;   // no such table (yet)
    if (!never) return true;
    if (!holdsAtLeast(c, schema, table, MIN_ROWS)) return false;
    String qualified = quoteIdent(schema) + "." + quoteIdent(table);
    if (!tryLock(c, schema + "." + table)) return false;
    try {
      if (!hasColumnStatistics(c, schema, table)) {
        try (Statement st = c.createStatement()) {
          st.execute("ANALYZE " + qualified);
        }
      }
      return true;
    } finally {
      unlock(c, schema + "." + table);
    }
  }

  private static boolean tryLock(Connection c, String key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
      ps.setString(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  private static void unlock(Connection c, String key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
      ps.setString(1, key);
      ps.execute();
    }
  }

  private static boolean hasColumnStatistics(Connection c, String schema, String table) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT EXISTS (SELECT 1 FROM pg_stats WHERE schemaname = ? AND tablename = ?)")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  /** {@code null} when the table does not exist. */
  private static Boolean neverAnalysed(Connection c, String schema, String table) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT last_analyze IS NULL AND last_autoanalyze IS NULL "
        + "FROM pg_stat_user_tables WHERE schemaname = ? AND relname = ?")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBoolean(1) : null;
      }
    }
  }

  /** Counted, not read from the statistics: a table nothing has analysed has none to read. */
  private static boolean holdsAtLeast(Connection c, String schema, String table, int rows) throws SQLException {
    try (Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT count(*) FROM (SELECT 1 FROM " + quoteIdent(schema) + "."
             + quoteIdent(table) + " LIMIT " + rows + ") s")) {
      return rs.next() && rs.getLong(1) >= rows;
    }
  }
}
