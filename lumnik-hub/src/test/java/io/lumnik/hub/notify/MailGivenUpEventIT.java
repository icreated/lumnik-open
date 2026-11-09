package io.lumnik.hub.notify;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.mailer.Mail;
import io.quarkus.test.InjectMock;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * #1906: a mail given up is told through the event log, a road that does not depend on the mail — one event per
 * delivery, when it becomes given up, carrying the kind of failure and never the mail.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class MailGivenUpEventIT {

    private static final String GIVEN_UP = "notification.mail.given-up";

    @InjectMock MailTransport transport;
    @Inject OutboxSweeper sweeper;
    @Inject EmailConsumer consumer;
    @Inject MailReplayService replay;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional
    void seed() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_outbox").executeUpdate();
        em.createNativeQuery("DELETE FROM core.notification_delivery WHERE succeeded = FALSE AND attempts < 3").executeUpdate();
        em.createNativeQuery("DELETE FROM core.notification_delivery WHERE recipient LIKE '%@given.local'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.notification_rule WHERE recipient LIKE '%@given.local'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_consumer WHERE consumer_name = 'email'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.notification_rule (tenant_id, event_pattern, recipient)
            VALUES (1, 'given.test.*', 'ops@given.local')
            """).executeUpdate();
        em.persist(EventOutboxRow.of(1L, "given.test.fired", Map.of("detail", "frost")));
    }

    @Transactional
    List<?> givenUpEvents() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return em.createNativeQuery("""
            SELECT tenant_id, payload->>'delivery_id', payload->>'event_type', payload->>'failure',
                   (SELECT string_agg(k, ',' ORDER BY k) FROM jsonb_object_keys(payload) k)
              FROM core.event_outbox WHERE event_type = :t ORDER BY id""")
            .setParameter("t", GIVEN_UP).getResultList();
    }

    @Transactional
    long deliveryId() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery(
            "SELECT id FROM core.notification_delivery WHERE recipient = 'ops@given.local'").getSingleResult()).longValue();
    }

    private void sweepOneBatch() {
        TenantContext.runAsSystem(() -> {
            var processor = consumer.batchProcessor();
            sweeper.sweep("email", 100, processor);
            processor.afterCommit();
        });
    }

    /** The first attempt in the outbox batch, then two retries: the third failure gives the mail up. */
    private void giveUpByRetries() {
        sweepOneBatch();
        TenantContext.runAsSystem(consumer::retryBatch);
        TenantContext.runAsSystem(consumer::retryBatch);
    }

    @Test
    void a_mail_is_told_once_when_its_third_attempt_fails_and_not_before() {
        doThrow(new IllegalStateException("550 ops@given.local rejected")).when(transport).send(any(Mail.class));

        sweepOneBatch();
        TenantContext.runAsSystem(consumer::retryBatch);
        assertTrue(givenUpEvents().isEmpty(), "two attempts: still being retried, nothing to tell");

        TenantContext.runAsSystem(consumer::retryBatch);
        List<?> told = givenUpEvents();
        assertEquals(1, told.size());
        Object[] row = (Object[]) told.get(0);
        assertEquals(1L, ((Number) row[0]).longValue(), "the event belongs to the delivery's tenant");
        assertEquals(String.valueOf(deliveryId()), row[1]);
        assertEquals("given.test.fired", row[2]);
        assertEquals("IllegalStateException", row[3]);
        assertEquals("delivery_id,event_type,failure,rule_id", row[4], "never the mail: only these keys");
    }

    @Test
    void a_mail_that_cannot_be_rendered_is_told_at_once() {
        setTemplate("no-such-template");

        sweepOneBatch();

        List<?> told = givenUpEvents();
        assertEquals(1, told.size());
        assertEquals("template: unknown", ((Object[]) told.get(0))[3]);
    }

    @Test
    void a_replay_that_fails_again_does_not_tell_a_second_time() {
        doThrow(new IllegalStateException("down")).when(transport).send(any(Mail.class));
        giveUpByRetries();
        assertEquals(1, givenUpEvents().size());

        replay.replay(1L, deliveryId());

        assertEquals(1, givenUpEvents().size(), "the delivery was already told: a fourth attempt adds nothing");
    }

    @Test
    void a_mail_rule_never_mails_the_news_of_a_mail_given_up() {
        doThrow(new IllegalStateException("down")).when(transport).send(any(Mail.class));
        addRule("notification.*");
        giveUpByRetries();
        assertEquals(1, givenUpEvents().size());

        sweepOneBatch(); // the email consumer now reads the given-up event itself

        assertEquals(0, countDeliveries(GIVEN_UP), "a mail does not tell about mails: that would loop while the relay is down");
    }

    @Transactional
    void setTemplate(String name) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("UPDATE core.notification_rule SET template = :t WHERE recipient = 'ops@given.local'")
            .setParameter("t", name).executeUpdate();
    }

    @Transactional
    void addRule(String pattern) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("INSERT INTO core.notification_rule (tenant_id, event_pattern, recipient) VALUES (1, :p, 'meta@given.local')")
            .setParameter("p", pattern).executeUpdate();
    }

    @Transactional
    long countDeliveries(String eventType) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery("SELECT count(*) FROM core.notification_delivery WHERE event_type = :t")
            .setParameter("t", eventType).getSingleResult()).longValue();
    }
}
