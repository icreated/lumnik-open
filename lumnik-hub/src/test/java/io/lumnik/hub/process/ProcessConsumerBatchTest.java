package io.lumnik.hub.process;

import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.RowEventGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The process consumer reads (and parses) the processes listening on a tenant's table once per
 * outbox batch, not once per event, and never carries them into the next batch — so an applied,
 * disabled or deleted process is seen one batch later at most.
 */
class ProcessConsumerBatchTest {

    private final List<String> processReads = new ArrayList<>();
    private final ProcessConsumer consumer = new ProcessConsumer();

    ProcessConsumerBatchTest() {
        consumer.store = new ProcessManifestStore() {
            @Override public List<StoredProcess> findEnabledFor(long tenantId, String table) {
                processReads.add(tenantId + ":" + table);
                return List.of();
            }
        };
    }

    private static EventOutboxRow rowChanged(long tenant, String table) {
        return EventOutboxRow.ofFull(tenant, null, RowEventGate.DATA_ROW_CHANGED, "connector:test",
            table, "hash", Map.of(), null, Instant.now());
    }

    @Test
    void a_batch_reads_the_processes_on_each_tenants_table_once_and_the_next_batch_reads_them_again() {
        Consumer<EventOutboxRow> batch = consumer.batchProcessor();
        batch.accept(rowChanged(1, "connector.t_orders"));
        batch.accept(rowChanged(1, "connector.t_clients"));
        batch.accept(rowChanged(1, "connector.t_orders"));
        batch.accept(rowChanged(2, "connector.t_orders"));
        batch.accept(EventOutboxRow.of(1L, "connector.run.failed", Map.of()));   // not a row event: no read
        batch.accept(rowChanged(2, "connector.t_orders"));
        assertThat(processReads).containsExactly("1:connector.t_orders", "1:connector.t_clients", "2:connector.t_orders");

        consumer.batchProcessor().accept(rowChanged(1, "connector.t_orders"));
        assertThat(processReads).containsExactly(
            "1:connector.t_orders", "1:connector.t_clients", "2:connector.t_orders", "1:connector.t_orders");
    }
}
