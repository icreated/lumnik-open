package io.lumnik.hub.notify;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.BatchProcessor;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #900: the mail leaves after the batch (and its cursor) committed, with no transaction held. */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class EmailSendAfterCommitIT {

    @Inject OutboxSweeper sweeper;
    @Inject EmailConsumer consumer;
    @Inject MockMailbox mailbox;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional
    void seed() {
        mailbox.clear();
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_outbox").executeUpdate();
        em.createNativeQuery("DELETE FROM core.notification_rule WHERE recipient LIKE '%@aftercommit.local'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_consumer WHERE consumer_name = 'email'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.notification_rule (tenant_id, event_pattern, recipient)
            VALUES (1, 'after.commit.*', 'ops@aftercommit.local')
            """).executeUpdate();
        em.persist(EventOutboxRow.of(1L, "after.commit.fired", Map.of("detail", "frost")));
    }

    @Test
    void the_batch_alone_sends_nothing_the_mail_waits_for_after_commit() {
        var processor = consumer.batchProcessor();
        TenantContext.runAsSystem(() -> sweeper.sweep("email", 100, processor));

        assertTrue(mailbox.getMailsSentTo("ops@aftercommit.local").isEmpty(), "nothing is sent inside the batch transaction");

        processor.afterCommit();
        assertEquals(1, mailbox.getMailsSentTo("ops@aftercommit.local").size());
    }

    @Test
    void a_tick_sends_the_mail_once_the_batch_has_committed() {
        TenantContext.runAsSystem(() -> sweeper.drain("email", 100, consumer::batchProcessor));

        assertEquals(1, mailbox.getMailsSentTo("ops@aftercommit.local").size());
    }

    @Test
    void the_send_step_of_a_tick_runs_with_no_transaction_open() {
        List<Boolean> active = new ArrayList<>();
        TenantContext.runAsSystem(() -> sweeper.drain("email", 100, () -> {
            BatchProcessor inner = consumer.batchProcessor();
            return new BatchProcessor() {
                @Override public void accept(EventOutboxRow row) { inner.accept(row); }

                @Override public void afterCommit() {
                    active.add(QuarkusTransaction.isActive());
                    inner.afterCommit();
                }
            };
        }));

        assertEquals(List.of(false), active);
        assertEquals(1, mailbox.getMailsSentTo("ops@aftercommit.local").size());
    }
}
