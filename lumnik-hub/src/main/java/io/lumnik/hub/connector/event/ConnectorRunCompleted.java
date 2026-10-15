package io.lumnik.hub.connector.event;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Fired in-process at connector-run finalization (alongside the outbox "connector.run.completed").
 * Consumed by ask's SchemaCardRefresher. Deliberately NOT an outbox payload — an in-process concern.
 *
 * <p>{@code tablesWritten}: every table the run's {@code DynamicTableWriter} wrote, unquoted
 * {@code schema.table} — the keys of the outbox payload's {@code tables}, the only writer that stamps
 * {@code _run_id}. The incremental resolution pass reads only the sources named here (#904). {@code
 * null} means unknown, and every source is read: the four-argument constructor, for a firer that
 * cannot tell, and a run whose own ending lost the race to another (whose drain may have emptied the
 * list first).
 */
public record ConnectorRunCompleted(UUID connectorId, long tenantId, UUID runId, Instant runStartedAt,
                                    Set<String> tablesWritten) {

  public ConnectorRunCompleted(UUID connectorId, long tenantId, UUID runId, Instant runStartedAt) {
    this(connectorId, tenantId, runId, runStartedAt, null);
  }
}
