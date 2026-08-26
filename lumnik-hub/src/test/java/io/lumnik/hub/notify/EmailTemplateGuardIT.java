package io.lumnik.hub.notify;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.qute.Engine;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * notification_rule.template is DB-sourced (tenant-configurable) and concatenated into the
 * Qute template id, so a path-traversal value (e.g. "../event-notification") must be rejected
 * before lookup. Engine is mocked so the assertion holds regardless of Qute's own resolution:
 * an unsafe name must never reach getTemplate; a safe one must.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class EmailTemplateGuardIT {

    @InjectMock Engine qute;
    @Inject EmailConsumer consumer;
    @Inject OutboxSweeper sweeper;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional
    void clean() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_outbox").executeUpdate();
        em.createNativeQuery("DELETE FROM core.notification_rule WHERE recipient LIKE '%@guardtest.local'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_consumer WHERE consumer_name = 'email'").executeUpdate();
    }

    @Transactional
    void seedRule(String template, String recipient) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.notification_rule (tenant_id, event_pattern, recipient, template)
            VALUES (1, 'guard.test.*', :r, :t)
            """).setParameter("r", recipient).setParameter("t", template).executeUpdate();
    }

    @Transactional
    void seedEvent(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.persist(EventOutboxRow.of(1L, type, Map.of("k", "v")));
    }

    @Test
    void unsafeTemplateNameNeverReachesTemplateLookup() {
        seedRule("../event-notification", "evil@guardtest.local"); // path traversal
        seedRule("event-notification", "ok@guardtest.local");      // safe
        seedEvent("guard.test.fire");

        TenantContext.runAsSystem(() -> sweeper.sweep("email", 100, consumer::process));

        verify(qute, never()).getTemplate(contains("..")); // guard blocked the traversal
        verify(qute).getTemplate("email/event-notification.html"); // safe name passed through
    }
}
