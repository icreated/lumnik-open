package io.lumnik.hub.notify;

import io.lumnik.hub.event.EventOutboxRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The email consumer reads a tenant's notification rules once per outbox batch, not once per event,
 * and never carries them into the next batch — so a rule change is seen one batch later at most.
 */
class EmailConsumerBatchTest {

    private final List<Long> ruleReads = new ArrayList<>();
    private final EmailConsumer consumer = new EmailConsumer();

    EmailConsumerBatchTest() {
        consumer.rules = new NotificationRuleRepository() {
            @Override public List<NotificationRule> findEnabled(Long tenantId) {
                ruleReads.add(tenantId);
                return List.of();
            }
        };
    }

    @Test
    void a_batch_reads_each_tenants_rules_once_and_the_next_batch_reads_them_again() {
        Consumer<EventOutboxRow> batch = consumer.batchProcessor();
        for (long tenant : new long[] {1, 2, 1, 1, 2}) batch.accept(EventOutboxRow.of(tenant, "connector.run.failed", Map.of()));
        assertThat(ruleReads).containsExactly(1L, 2L);

        consumer.batchProcessor().accept(EventOutboxRow.of(1L, "connector.run.failed", Map.of()));
        assertThat(ruleReads).containsExactly(1L, 2L, 1L);
    }
}
