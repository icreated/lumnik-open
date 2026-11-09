package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.mapping.writer.DynamicTableRegistry;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.RowEventGate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * The process consumer reads (and parses) the processes listening on a tenant's table once per
 * outbox batch, not once per event, and never carries them into the next batch — so an applied,
 * disabled or deleted process is seen one batch later at most.
 */
class ProcessConsumerBatchTest {

    private final List<String> processReads = new ArrayList<>();
    private final List<String> entityReads = new ArrayList<>();
    private final ProcessConsumer consumer = new ProcessConsumer();

    /** No process is declared on an Entity: the table's fusing Entities are asked once, and nothing answers. */
    private EntityProcessTargets noEntityProcesses() {
        return new EntityProcessTargets() {
            @Override public List<Target> observing(long tenantId, String sourceTable) {
                entityReads.add(tenantId + ":" + sourceTable);
                return List.of();
            }
            @Override public List<Target> joining(long tenantId, String lookupTable) { return List.of(); }
        };
    }

    ProcessConsumerBatchTest() {
        consumer.entityTargets = noEntityProcesses();
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
        // No registry is set: a table nobody listens on must never ask it for a hash column.
        Consumer<EventOutboxRow> batch = consumer.batchProcessor();
        batch.accept(rowChanged(1, "connector.t_orders"));
        batch.accept(rowChanged(1, "connector.t_clients"));
        batch.accept(rowChanged(1, "connector.t_orders"));
        batch.accept(rowChanged(2, "connector.t_orders"));
        batch.accept(EventOutboxRow.of(1L, "connector.run.failed", Map.of()));   // not a row event: no read
        batch.accept(rowChanged(2, "connector.t_orders"));
        assertThat(processReads).containsExactly("1:connector.t_orders", "1:connector.t_clients", "2:connector.t_orders");
        // #1833: the Entity processes a table reaches are read as rarely as the table's own
        assertThat(entityReads).containsExactly("1:connector.t_orders", "1:connector.t_clients", "2:connector.t_orders");

        consumer.batchProcessor().accept(rowChanged(1, "connector.t_orders"));
        assertThat(processReads).containsExactly(
            "1:connector.t_orders", "1:connector.t_clients", "2:connector.t_orders", "1:connector.t_orders");
        assertThat(entityReads).hasSize(4);
    }

    /** #1816: a table's hash column comes from the registry, asked as rarely as its processes are read. */
    @Test
    void a_batch_asks_the_registry_for_each_tenants_hash_column_once_and_the_next_batch_asks_again() {
        List<String> hashReads = new ArrayList<>();
        ProcessConsumer listening = new ProcessConsumer();
        listening.store = new ProcessManifestStore() {
            @Override public List<StoredProcess> findEnabledFor(long tenantId, String table) {
                return List.of(new StoredProcess("p", null, table, List.of(), "decision.p", "m", List.of(), null, true));
            }
        };
        listening.tables = new DynamicTableRegistry() {
            @Override public String rowHashColumn(Long tenantId, String fqn) {
                hashReads.add(tenantId + ":" + fqn);
                return "_row_hash";
            }
        };
        listening.adminDs = mock(AgroalDataSource.class, RETURNS_DEEP_STUBS);   // every row read finds nothing
        listening.entityTargets = noEntityProcesses();

        Consumer<EventOutboxRow> batch = listening.batchProcessor();
        batch.accept(rowChanged(1, "connector.t_orders"));
        batch.accept(rowChanged(1, "ext.samples"));
        batch.accept(rowChanged(1, "connector.t_orders"));
        batch.accept(rowChanged(2, "connector.t_orders"));
        batch.accept(rowChanged(1, "ext.samples"));
        assertThat(hashReads).containsExactly("1:connector.t_orders", "1:ext.samples", "2:connector.t_orders");

        listening.batchProcessor().accept(rowChanged(1, "ext.samples"));
        assertThat(hashReads).containsExactly(
            "1:connector.t_orders", "1:ext.samples", "2:connector.t_orders", "1:ext.samples");
    }
}
