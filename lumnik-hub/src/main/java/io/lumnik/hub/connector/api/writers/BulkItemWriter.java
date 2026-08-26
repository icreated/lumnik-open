package io.lumnik.hub.connector.api.writers;

import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;

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
  @Override
  public WriteOutcome write(List<T> items, ConnectorContext ctx) {
    if (items.isEmpty()) {
      return new WriteOutcome.AllWritten(0);
    }
    DataSource ds = CDI.current().select(DataSource.class).get();
    try (Connection conn = ds.getConnection();
         PreparedStatement ps = conn.prepareStatement(insertSql())) {
      for (var item : items) {
        bindRow(ps, item);
        ps.addBatch();
      }
      ps.executeBatch();
      return new WriteOutcome.AllWritten(items.size());
    } catch (Exception e) {
      return new WriteOutcome.AllFailed(e.getMessage(), e);
    }
  }
}
