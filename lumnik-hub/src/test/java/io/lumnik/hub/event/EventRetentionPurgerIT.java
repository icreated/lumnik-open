package io.lumnik.hub.event;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class EventRetentionPurgerIT {

    @Inject EventRetentionPurger purger;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional
    void clean() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_outbox WHERE event_type LIKE 'purge.test.%'").executeUpdate();
        // Scheduler is paused (profile): safe to clear ALL cursors so MIN() is fully ours.
        em.createNativeQuery("DELETE FROM core.event_consumer").executeUpdate();
    }

    @Transactional
    long seedOldEvent(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.event_outbox (tenant_id, event_type, payload, occurred_at, created_at)
            VALUES (1, :t, '{}'::jsonb, NOW() - INTERVAL '60 days', NOW() - INTERVAL '60 days')
            """).setParameter("t", type).executeUpdate();
        return ((Number) em.createNativeQuery(
            "SELECT max(id) FROM core.event_outbox WHERE event_type = :t")
            .setParameter("t", type).getSingleResult()).longValue();
    }

    @Transactional
    void seedConsumer(String name, long lastId) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery(
            "INSERT INTO core.event_consumer (consumer_name, last_event_id) VALUES (:n, :i)")
          .setParameter("n", name).setParameter("i", lastId).executeUpdate();
    }

    @Transactional
    long countTestEvents() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM core.event_outbox WHERE event_type LIKE 'purge.test.%'")
            .getSingleResult()).longValue();
    }

    @Test
    void purgesOnlyBelowSlowestConsumer() {
        long first = seedOldEvent("purge.test.old1");
        long second = seedOldEvent("purge.test.old2");
        // fast consumer has passed both; slow consumer only the first
        seedConsumer("purge-fast", second);
        seedConsumer("purge-slow", first);

        TenantContext.runAsSystem(purger::purge);

        // only the first event (id <= min cursor) is purged, despite both being 60 days old
        assertEquals(1, countTestEvents());
    }

    @Test
    void noConsumersMeansNoPurge() {
        seedOldEvent("purge.test.lonely");
        TenantContext.runAsSystem(purger::purge);
        assertEquals(1, countTestEvents());
    }
}
