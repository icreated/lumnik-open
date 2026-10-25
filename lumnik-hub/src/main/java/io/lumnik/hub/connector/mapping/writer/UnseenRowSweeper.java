package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.search.mark.SearchProjectionRunHook;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * Counts, then removes, the rows of one source a full read of it did not meet again (#1195, see
 * {@code CsvFullRefresh}, which holds the guards). The guard is how much the table would SHRINK: a
 * changed row is one version met and one unmet, so the unmet alone would refuse a refresh that
 * changed many values and lost nothing. A row another source landed is neither counted nor removed.
 * The DELETE commits on the admin datasource, as the writer's inserts do: a chunk that fails
 * afterwards does not bring the rows back.
 */
@ApplicationScoped
public class UnseenRowSweeper {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  @Inject SearchProjectionRunHook searchMarks;

  /**
   * One source's rows as a full read of it left them: {@code before} it ran (not landed by this run),
   * {@code met} by it, and {@code unseen} — what a sweep would remove.
   */
  public record Count(long before, long met, long unseen) {

    /**
     * How much the table shrinks: the rows the source lost (#1544). A changed row is one of the
     * {@code unseen} versions but no loss — its new version was met. Without a key, a row lost beside
     * a row added reads as a change: this is the loss the counts prove, never more.
     */
    public long lost() {
      return Math.max(0, before - met);
    }

    /** Would the table shrink by more than {@code maxRemoved} of what it held? 1 lifts the limit. */
    public boolean shrinksBeyond(double maxRemoved) {
      return maxRemoved < 1 && lost() > before * maxRemoved;
    }
  }

  /** Counts, removing nothing: the caller's guards decide whether {@link #remove} runs. */
  public Count count(MappingTarget.Table spec, long tenantId, String source, UUID runId) {
    String table = table(spec);
    try (Connection c = adminDs.getConnection()) {
      if (!exists(c, table)) return new Count(0, 0, 0);
      if (!hasSeenColumn(c, spec)) {
        // A table older than #1195 gets the column from the first chunk that writes a row; a run that
        // wrote none (an empty file, every row dead-lettered) met nothing, and the guards decide.
        long held = held(c, table, tenantId, source);
        return new Count(held, 0, held);
      }
      try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FILTER (WHERE _run_id IS DISTINCT FROM ?), "
          + "count(*) FILTER (WHERE " + DynamicTableWriter.SEEN_RUN + " = ?), "
          + "count(*) FILTER (WHERE " + DynamicTableWriter.SEEN_RUN + " IS DISTINCT FROM ?) FROM " + table + MINE)) {
        ps.setObject(1, runId);
        ps.setObject(2, runId);
        ps.setObject(3, runId);
        ps.setLong(4, tenantId);
        ps.setString(5, source);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return new Count(rs.getLong(1), rs.getLong(2), rs.getLong(3));
        }
      }
    } catch (SQLException e) {
      throw new ConnectorException("UnseenRowSweeper.count failed: " + e.getMessage(), e);
    }
  }

  /** Removes the source's rows this run did not meet; the indexes over the table then rebuild. */
  public long remove(MappingTarget.Table spec, long tenantId, String source, UUID runId) {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement("DELETE FROM " + table(spec) + MINE
             + " AND " + DynamicTableWriter.SEEN_RUN + " IS DISTINCT FROM ?")) {
      ps.setLong(1, tenantId);
      ps.setString(2, source);
      ps.setObject(3, runId);
      long removed = ps.executeUpdate();
      if (removed > 0) searchMarks.tableLostRows(tenantId, spec.name());
      return removed;
    } catch (SQLException e) {
      throw new ConnectorException("UnseenRowSweeper.remove failed: " + e.getMessage(), e);
    }
  }

  private static final String MINE = " WHERE tenant_id = ? AND _source IS NOT DISTINCT FROM ?";

  private static String table(MappingTarget.Table spec) {
    return quoteIdent(spec.schema()) + "." + quoteIdent(spec.name());
  }

  private static boolean hasSeenColumn(Connection c, MappingTarget.Table spec) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_attribute "
        + "WHERE attrelid = to_regclass(?) AND attname = ? AND NOT attisdropped)")) {
      ps.setString(1, table(spec));
      ps.setString(2, DynamicTableWriter.SEEN_RUN);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  private static long held(Connection c, String table, long tenantId, String source) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + table + MINE)) {
      ps.setLong(1, tenantId);
      ps.setString(2, source);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  static boolean exists(Connection c, String table) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }
}
