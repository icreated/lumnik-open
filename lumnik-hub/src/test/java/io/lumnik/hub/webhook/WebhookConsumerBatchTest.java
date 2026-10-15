package io.lumnik.hub.webhook;

import io.lumnik.hub.event.EventOutboxRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The webhook consumer reads a tenant's live endpoints once per outbox batch, not once per event,
 * and never carries them into the next batch — so an endpoint change is seen one batch later at most.
 */
class WebhookConsumerBatchTest {

    private final List<Long> endpointReads = new ArrayList<>();
    private final WebhookConsumer consumer = new WebhookConsumer();

    WebhookConsumerBatchTest() {
        consumer.endpointRepo = new WebhookEndpointRepository() {
            @Override public List<WebhookEndpoint> findEnabled(Long tenantId) {
                endpointReads.add(tenantId);
                return List.of();
            }
        };
    }

    @Test
    void a_batch_reads_each_tenants_endpoints_once_and_the_next_batch_reads_them_again() {
        Consumer<EventOutboxRow> batch = consumer.batchProcessor();
        for (long tenant : new long[] {1, 2, 1, 1, 2}) batch.accept(EventOutboxRow.of(tenant, "order.created", Map.of()));
        assertThat(endpointReads).containsExactly(1L, 2L);

        consumer.batchProcessor().accept(EventOutboxRow.of(1L, "order.created", Map.of()));
        assertThat(endpointReads).containsExactly(1L, 2L, 1L);
    }
}
