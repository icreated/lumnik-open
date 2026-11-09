package io.lumnik.hub.notify;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventDeadLetters;
import io.lumnik.hub.event.EventOutboxRow;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #1848: a mail is one durable row per rule and event, recorded by the batch and sent from that row. */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class EmailDeliveryIT {

    @Inject OutboxSweeper sweeper;
    @Inject EmailConsumer consumer;
    @Inject EventDeadLetters deadLetters;
    @Inject MockMailbox mailbox;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional
    void clean() {
        mailbox.clear();
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_outbox").executeUpdate();
        em.createNativeQuery("DELETE FROM core.notification_delivery WHERE recipient LIKE '%@delivery.local'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_dead_letter WHERE consumer_name = 'email'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.notification_rule WHERE recipient LIKE '%@delivery.local'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_consumer WHERE consumer_name = 'email'").executeUpdate();
    }

    @Transactional
    void seedRule(String recipient, String template) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.notification_rule (tenant_id, event_pattern, recipient, template)
            VALUES (1, 'delivery.test.*', :r, :t)
            """).setParameter("r", recipient).setParameter("t", template).executeUpdate();
    }

    @Transactional
    void seedEvent() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.persist(EventOutboxRow.of(1L, "delivery.test.fired", Map.of("detail", "frost")));
    }

    @Transactional
    @SuppressWarnings("unchecked")
    List<Object[]> deliveries() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return em.createNativeQuery("""
            SELECT recipient, succeeded, attempts, error FROM core.notification_delivery
             WHERE recipient LIKE '%@delivery.local' ORDER BY recipient
            """).getResultList();
    }

    @Transactional
    Number scalar(String sql) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return (Number) em.createNativeQuery(sql).getSingleResult();
    }

    /** The door's caller: an integrator of tenant 1. */
    private EventDeadLetters.Replay replayAsTenantOne(long letter) {
        CurrentTenantHolder.set(1L);
        try {
            return deadLetters.replay(letter);
        } finally {
            CurrentTenantHolder.clear();
        }
    }

    /** A whole-event letter for the e-mail consumer, as the sweeper leaves one. */
    private long openLetter(long eventId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.createNativeQuery("SET LOCAL app.current_tenant = '1'").executeUpdate();
            deadLetters.record("email", eventId, EventDeadLetters.WHOLE_EVENT, new IllegalStateException("half-way"));
            return ((Number) em.createNativeQuery("SELECT id FROM core.event_dead_letter WHERE consumer_name = 'email'")
                .getSingleResult()).longValue();
        });
    }

    private void sweepOneBatch() {
        TenantContext.runAsSystem(() -> {
            var processor = consumer.batchProcessor();
            sweeper.sweep("email", 100, processor);
            processor.afterCommit();
        });
    }

    @Test
    void aRuleThatCannotRenderIsAGivenUpDeliveryAndTheOtherRecipientIsStillMailed() {
        seedRule("broken@delivery.local", "test-broken");
        seedRule("fine@delivery.local", "event-notification");
        seedEvent();

        sweepOneBatch();

        assertEquals(1, mailbox.getMailsSentTo("fine@delivery.local").size());
        assertTrue(mailbox.getMailsSentTo("broken@delivery.local").isEmpty());
        List<Object[]> rows = deliveries();
        assertEquals(2, rows.size(), "the failure is a row, not a log line");
        assertEquals("broken@delivery.local", rows.get(0)[0]);
        assertFalse((Boolean) rows.get(0)[1]);
        assertEquals(3, ((Number) rows.get(0)[2]).intValue(), "a render is deterministic: given up at once, never retried");
        assertTrue(String.valueOf(rows.get(0)[3]).startsWith("render"), "the kind of failure: " + rows.get(0)[3]);
        assertTrue((Boolean) rows.get(1)[1]);
        assertEquals(1, ((Number) rows.get(1)[2]).intValue());
    }

    @Test
    void anEventRecordedTwiceMailsEachRecipientOnce() {
        seedRule("a@delivery.local", "event-notification");
        seedEvent();
        sweepOneBatch();
        // the cursor goes back, as after a batch whose commit failed: the same event is read again
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
            em.createNativeQuery("DELETE FROM core.event_consumer WHERE consumer_name = 'email'").executeUpdate();
        });
        sweepOneBatch();

        assertEquals(1, mailbox.getMailsSentTo("a@delivery.local").size());
        assertEquals(1, deliveries().size());
    }

    @Test
    void aReplayedEventRecordsOnlyTheRecipientsWithoutADeliveryAndMailsNobodyTwice() {
        seedRule("a@delivery.local", "event-notification");
        seedRule("b@delivery.local", "event-notification");
        seedEvent();
        sweepOneBatch();
        assertEquals(2, deliveries().size());
        // b never got its delivery: the consumer failed on the event half-way
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
            em.createNativeQuery("DELETE FROM core.notification_delivery WHERE recipient = 'b@delivery.local'").executeUpdate();
        });
        mailbox.clear();
        long eventId = scalar("SELECT MAX(id) FROM core.event_outbox").longValue();
        long letter = openLetter(eventId);

        assertEquals(EventDeadLetters.Status.DONE, replayAsTenantOne(letter).status());

        assertEquals(2, deliveries().size(), "b is recorded again, a is not");
        assertTrue(mailbox.getMailsSentTo("a@delivery.local").isEmpty(), "a was served: not mailed again");
        // the replay records and leases; the retry sweep sends once the lease has run out
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
            em.createNativeQuery("UPDATE core.notification_delivery SET claimed_until = now() - interval '1 second' "
                + "WHERE recipient LIKE '%@delivery.local'").executeUpdate();
        });
        TenantContext.runAsSystem(consumer::retryBatch);
        assertEquals(1, mailbox.getMailsSentTo("b@delivery.local").size());
        assertTrue(mailbox.getMailsSentTo("a@delivery.local").isEmpty());
    }

    @Test
    void aReplayThatRecordsNothingIsAFailureAndLeavesTheLetterOpen() {
        seedRule("a@delivery.local", "event-notification");
        seedEvent();
        sweepOneBatch();
        long eventId = scalar("SELECT MAX(id) FROM core.event_outbox").longValue();
        long letter = openLetter(eventId);

        assertThrows(IllegalStateException.class, () -> replayAsTenantOne(letter), "every recipient was served already");
        assertEquals(0, scalar("SELECT count(*) FROM core.event_dead_letter WHERE id = " + letter + " AND replayed_at IS NOT NULL").intValue());
    }
}
