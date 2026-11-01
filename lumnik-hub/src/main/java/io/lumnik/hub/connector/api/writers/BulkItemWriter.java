package io.lumnik.hub.connector.api.writers;

import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;
import io.lumnik.hub.sql.SqlFailureText;

import jakarta.enterprise.inject.spi.CDI;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * Base class for batch writes via JDBC PreparedStatement batches.
 * <p>
 * Executes a single INSERT statement with multiple parameter sets, using JDBC batch mode.
 * Profile: {@link WritePerformanceProfile.Bulk Bulk}.
 * </p>
 */
public abstract class BulkItemWriter<T> implements ItemWriter<T> {

  /**
   * Returns Bulk profile (batch INSERT, good throughput).
   */
  public WritePerformanceProfile profile() {
    return new WritePerformanceProfile.Bulk();
  }

  /**
   * Returns the INSERT SQL statement (parameterized).
   * <p>
   * Example: {@code "INSERT INTO users (name, email) VALUES (?, ?)"}
   * </p>
   */
  protected abstract String insertSql();

  /**
   * Binds a single item to the PreparedStatement before adding it to the batch.
   *
   * @param ps   the prepared statement
   * @param item the item to bind
   * @throws Exception if binding fails
   */
  protected abstract void bindRow(PreparedStatement ps, T item) throws Exception;

  /**
   * Executes a batch INSERT for all items.
   * <p>
   * If the list is empty, returns {@link WriteOutcome.AllWritten AllWritten}(0).
   * On error, returns {@link WriteOutcome.AllFailed AllFailed}.
   * </p>
   */
  /** The datasource the batch runs on; a seam so a test can hand a failing one. */
  protected DataSource dataSource() {
    return CDI.current().select(DataSource.class).get();
  }

  @Override
  public WriteOutcome write(List<T> items, ConnectorContext ctx) {
    if (items.isEmpty()) {
      return new WriteOutcome.AllWritten(0);
    }
    DataSource ds = dataSource();
    try (Connection conn = ds.getConnection();
         PreparedStatement ps = conn.prepareStatement(insertSql())) {
      for (var item : items) {
        bindRow(ps, item);
        ps.addBatch();
      }
      ps.executeBatch();
      return new WriteOutcome.AllWritten(items.size());
    } catch (Exception e) {
      // The same rule as ChunkExecutor.failure: a batch failure quotes its row, and class 22/23 text is withheld (#1679).
      return new WriteOutcome.AllFailed(SqlFailureText.withCause(e), e);
    }
  }
}
