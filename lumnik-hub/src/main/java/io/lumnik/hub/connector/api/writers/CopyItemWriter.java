package io.lumnik.hub.connector.api.writers;

import io.lumnik.hub.connector.api.ItemWriter;
import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.api.types.WriteOutcome;
import io.lumnik.hub.connector.api.types.WritePerformanceProfile;

import jakarta.enterprise.inject.spi.CDI;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;

import javax.sql.DataSource;
import java.io.StringReader;
import java.sql.Connection;
import java.util.List;

/**
 * Base class for bulk loads via PostgreSQL COPY.
 * <p>
 * Uses PostgreSQL's native COPY FROM STDIN for maximum throughput.
 * Requires PostgreSQL JDBC driver (provided by quarkus-jdbc-postgresql).
 * Profile: {@link WritePerformanceProfile.BulkLoad BulkLoad}.
 * </p>
 */
public abstract class CopyItemWriter<T> implements ItemWriter<T> {

  /**
   * Returns BulkLoad profile (COPY FROM STDIN, maximum throughput).
   */
  public WritePerformanceProfile profile() {
    return new WritePerformanceProfile.BulkLoad();
  }

  /**
   * Returns the COPY command (e.g., {@code "COPY users (id, name, email) FROM STDIN"}).
   * <p>
   * The CSV format is assumed (default: pipe-delimited).
   * </p>
   */
  protected abstract String copyCommand();

  /**
   * Converts a single item to a CSV row (newline-separated fields).
   * <p>
   * Example: {@code "42|john.doe|john@example.com"}
   * </p>
   *
   * @param item the item to convert
   * @return the CSV row (without trailing newline)
   */
  protected abstract String toCsvRow(T item);

  /**
   * Executes a COPY FROM for all items.
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
    try (Connection conn = ds.getConnection()) {
      var pg = conn.unwrap(BaseConnection.class);
      var copy = new CopyManager(pg);
      var sb = new StringBuilder();
      for (var item : items) {
        sb.append(toCsvRow(item)).append('\n');
      }
      long rows = copy.copyIn(copyCommand(), new StringReader(sb.toString()));
      return new WriteOutcome.AllWritten((int) rows);
    } catch (Exception e) {
      return new WriteOutcome.AllFailed(e.getMessage(), e);
    }
  }
}
