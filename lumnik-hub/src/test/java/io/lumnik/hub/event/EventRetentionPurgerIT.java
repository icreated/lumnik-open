package io.lumnik.hub.event;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow.Position;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class EventRetentionPurgerIT {

    @Inject EventRetentionPurger purger;
    @Inject EventHorizonWatch watch;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional
    void clean() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_outbox WHERE event_type LIKE 'purge.test.%'").executeUpdate();
        // Scheduler is paused (profile): safe to clear ALL cursors so MIN() is fully ours.
        em.createNativeQuery("DELETE FROM core.event_consumer").executeUpdate();
    }

    /** A 60-day-old event, and its place in the reading order (xact_id, id). */
    @Transactional
    Position seedOldEvent(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.event_outbox (tenant_id, event_type, payload, occurred_at, created_at)
            VALUES (1, :t, '{}'::jsonb, NOW() - INTERVAL '60 days', NOW() - INTERVAL '60 days')
            """).setParameter("t", type).executeUpdate();
        return position(type);
    }

    /** The same, with an explicit transaction id: fabricates a later id from an earlier transaction. */
    @Transactional
    Position seedOldEvent(String type, long xactId) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.event_outbox (tenant_id, event_type, payload, occurred_at, created_at, xact_id)
            VALUES (1, :t, '{}'::jsonb, NOW() - INTERVAL '60 days', NOW() - INTERVAL '60 days', :x)
            """).setParameter("t", type).setParameter("x", xactId).executeUpdate();
        return position(type);
    }

    private Position position(String type) {
        Object[] row = (Object[]) em.createNativeQuery(
            "SELECT xact_id, id FROM core.event_outbox WHERE event_type = :t ORDER BY id DESC LIMIT 1")
            .setParameter("t", type).getSingleResult();
        return new Position(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
    }

    /** The fabricated transaction ids stand above any real one: never leave them to the next class. */
    @AfterEach
    @Transactional
    void dropTestEvents() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_outbox WHERE event_type LIKE 'purge.test.%'").executeUpdate();
    }

    @Transactional
    void seedConsumer(String name, Position last) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery(
            "INSERT INTO core.event_consumer (consumer_name, last_xact_id, last_event_id) VALUES (:n, :x, :i)")
          .setParameter("n", name).setParameter("x", last.xact()).setParameter("i", last.id()).executeUpdate();
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
        Position first = seedOldEvent("purge.test.old1");
        Position second = seedOldEvent("purge.test.old2");
        // fast consumer has passed both; slow consumer only the first
        seedConsumer("purge-fast", second);
        seedConsumer("purge-slow", first);

        TenantContext.runAsSystem(purger::purge);

        // only the first event (id <= min cursor) is purged, despite both being 60 days old
        assertEquals(1, countTestEvents());
    }

    /**
     * #1570: consumers read in (xact_id, id) order. A row with a LOWER id but a LATER transaction comes
     * after a higher id of an earlier one: a consumer standing past that higher id has not read it yet,
     * and MIN(last_event_id) alone would purge it from under it.
     */
    @Test
    void aLowerIdFromALaterTransactionIsKeptUntilItIsRead() {
        Position later = seedOldEvent("purge.test.lower-id-later-xact", 2_000_000_000L);
        Position earlier = seedOldEvent("purge.test.higher-id-earlier-xact", 1_000_000_000L);
        assertTrue(earlier.id() > later.id() && earlier.xact() < later.xact(), "the fabricated order");
        seedConsumer("purge-reader", earlier);   // has read `earlier`, not yet `later`

        TenantContext.runAsSystem(purger::purge);

        assertEquals(1, countTestEvents(), "only the row the consumer has read is purged");
    }

    /**
     * The scheduled entry points, called as the scheduler calls them: {@code tick()} reaches the
     * {@code @Transactional} method through {@code this::}. ArC intercepts a self-invocation (its
     * subclass proxy is {@code this}), so the {@code SET LOCAL} inside runs in a transaction — the
     * purge does its work, the watch answers instead of throwing.
     */
    @Test
    void theScheduledTicksRunTheirWorkInATransaction() {
        Position old = seedOldEvent("purge.test.via-tick");
        seedConsumer("purge-tick", old);

        purger.tick();
        assertEquals(0, countTestEvents(), "the tick's purge ran, in a transaction");

        watch.tick();                 // SET LOCAL outside a transaction would throw here
    }

    @Test
    void noConsumersMeansNoPurge() {
        seedOldEvent("purge.test.lonely");
        TenantContext.runAsSystem(purger::purge);
        assertEquals(1, countTestEvents());
    }
}
