package io.lumnik.hub.notify;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.mailer.Mail;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.ext.mail.SMTPException;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** #1848: an SMTP failure belongs to the delivery — retried, then given up — and never to its neighbours. */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class EmailDeliveryRetryIT {

    @InjectMock MailTransport transport;
    @Inject NotificationDeliveries deliveries;
    @Inject OutboxSweeper sweeper;
    @Inject EmailConsumer consumer;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional
    void seed() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_outbox").executeUpdate();
        // the retry sweep claims globally: nothing else may be retryable while this test counts sends
        em.createNativeQuery("DELETE FROM core.notification_delivery WHERE succeeded = FALSE AND attempts < 3").executeUpdate();
        em.createNativeQuery("DELETE FROM core.notification_delivery WHERE recipient LIKE '%@retry.local'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.notification_rule WHERE recipient LIKE '%@retry.local'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_consumer WHERE consumer_name = 'email'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.notification_rule (tenant_id, event_pattern, recipient)
            VALUES (1, 'retry.test.*', 'ops@retry.local')
            """).executeUpdate();
        em.persist(EventOutboxRow.of(1L, "retry.test.fired", Map.of("detail", "frost")));
    }

    @Transactional
    Object[] delivery() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return (Object[]) em.createNativeQuery("""
            SELECT succeeded, attempts, error, claimed_until IS NULL FROM core.notification_delivery
             WHERE recipient = 'ops@retry.local'
            """).getSingleResult();
    }

    private void sweepOneBatch() {
        TenantContext.runAsSystem(() -> {
            var processor = consumer.batchProcessor();
            sweeper.sweep("email", 100, processor);
            processor.afterCommit();
        });
    }

    @Test
    void anSmtpFailureIsRetriedThenGivenUpWithOnlyTheKindOfFailureKept() {
        doThrow(new IllegalStateException("550 ops@retry.local rejected")).when(transport).send(any(Mail.class));

        sweepOneBatch();
        Object[] first = delivery();
        assertFalse((Boolean) first[0]);
        assertEquals(1, ((Number) first[1]).intValue(), "the first send is attempt 1");
        assertEquals("IllegalStateException", first[2], "the class, never the message: it quotes the address");
        assertTrue((Boolean) first[3], "the claim is released: the retry sweep may take it");

        TenantContext.runAsSystem(consumer::retryBatch);
        TenantContext.runAsSystem(consumer::retryBatch);
        assertEquals(3, ((Number) delivery()[1]).intValue());

        TenantContext.runAsSystem(consumer::retryBatch);
        verify(transport, times(3)).send(any(Mail.class));
        assertFalse((Boolean) delivery()[0]);
    }

    /** The mailer wraps the real failure in a CompletionException: the row says what failed, in the integrator's words. */
    @Test
    void aWrappedSmtpRefusalIsRecordedByItsKindNotByTheWrapper() {
        doThrow(new CompletionException(new SMTPException("535 ops@retry.local", 535, List.of("535 ops@retry.local"), true)))
            .when(transport).send(any(Mail.class));

        sweepOneBatch();

        assertEquals("authentication refused (SMTP 535)", delivery()[2]);
    }

    @Test
    void aRetrySucceedsOnceTheMailServerIsBack() {
        doThrow(new IllegalStateException("down")).doNothing().when(transport).send(any(Mail.class));

        sweepOneBatch();
        TenantContext.runAsSystem(consumer::retryBatch);

        Object[] row = delivery();
        assertTrue((Boolean) row[0]);
        assertEquals(2, ((Number) row[1]).intValue());
        verify(transport, times(2)).send(any(Mail.class));
        doNothing().when(transport).send(any(Mail.class));
        TenantContext.runAsSystem(consumer::retryBatch);
        verify(transport, times(2)).send(any(Mail.class));
    }

    /** A sender whose lease ran out and was taken over may not write the outcome: the new owner's is the one that counts. */
    @Test
    void aSenderWhoseLeaseWasTakenOverCannotWriteItsOutcome() {
        doThrow(new IllegalStateException("down")).when(transport).send(any(Mail.class));
        sweepOneBatch(); // attempt 1 fails: the delivery is unresolved and unclaimed

        NotificationDeliveries.Claimed stale = claim();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
            em.createNativeQuery("UPDATE core.notification_delivery SET claimed_until = now() - interval '1 second' "
                + "WHERE recipient = 'ops@retry.local'").executeUpdate();
        });
        NotificationDeliveries.Claimed owner = claim();

        assertFalse(finish(stale, null), "the stale sender's write is refused");
        assertFalse((Boolean) delivery()[0]);
        assertEquals(1, ((Number) delivery()[1]).intValue());
        assertTrue(finish(owner, null), "the current owner's write lands");
        assertTrue((Boolean) delivery()[0]);
    }

    private NotificationDeliveries.Claimed claim() {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
            return deliveries.claimNext(0, 60);
        });
    }

    private boolean finish(NotificationDeliveries.Claimed c, String failure) {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
            return deliveries.finish(c.id(), c.lease(), failure);
        });
    }
}
