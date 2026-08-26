package io.lumnik.hub.event.consumer;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

// Runs with live schedulers on purpose: its it-* cursors are private, so the webhook/email ticks can't interfere — and it doubles as proof that consumers coexist.
@QuarkusTest
class EventSpineIT {

    @Inject OutboxSweeper sweeper;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional
    void clean() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        // Empty the log so the freshly-seeded spine rows fall within a single sweep's
        // batch window (id > cursor LIMIT n): prior ITs in the shared DevServices DB
        // leave many low-id event_outbox rows that would otherwise fill the batch first.
        em.createNativeQuery("DELETE FROM core.event_outbox").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_consumer WHERE consumer_name LIKE 'it-%'").executeUpdate();
    }

    @Transactional
    void seed(long tenantId, String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.persist(EventOutboxRow.of(tenantId, type, Map.of("k", "v")));
    }

    @Test
    void twoConsumersAdvanceIndependently() {
        seed(1L, "spine.test.a");
        seed(2L, "spine.test.b");

        List<String> seenByOne = new CopyOnWriteArrayList<>();
        List<String> seenByTwo = new CopyOnWriteArrayList<>();

        TenantContext.runAsSystem(() -> sweeper.sweep("it-one", 100, r -> seenByOne.add(r.getEventType())));
        // it-two has not swept yet — its cursor must be untouched by it-one's progress
        TenantContext.runAsSystem(() -> sweeper.sweep("it-two", 100, r -> seenByTwo.add(r.getEventType())));

        assertTrue(seenByOne.containsAll(List.of("spine.test.a", "spine.test.b")));
        assertTrue(seenByTwo.containsAll(List.of("spine.test.a", "spine.test.b")));

        // second sweep sees nothing new
        seenByOne.clear();
        TenantContext.runAsSystem(() -> sweeper.sweep("it-one", 100, r -> seenByOne.add(r.getEventType())));
        assertTrue(seenByOne.stream().noneMatch(t -> t.startsWith("spine.test.")));
    }

    @Test
    void sweepRefusesToRunOutsideSystemMode() {
        // sweep sets the cross-tenant sentinel '0' directly, bypassing the interceptor's
        // spoofing guard — so it must fail closed if a non-system caller (a future REST
        // endpoint / CDI bean) ever invokes it without TenantContext.runAsSystem.
        assertFalse(TenantContext.isSystemMode());
        assertThrows(IllegalStateException.class,
            () -> sweeper.sweep("it-nonsystem", 100, r -> {}));
    }

    @Test
    void rewindReplays() {
        seed(1L, "spine.test.replay");
        List<String> seen = new CopyOnWriteArrayList<>();
        TenantContext.runAsSystem(() -> sweeper.sweep("it-rewind", 100, r -> seen.add(r.getEventType())));
        assertEquals(1, seen.stream().filter("spine.test.replay"::equals).count());

        rewind("it-rewind");
        TenantContext.runAsSystem(() -> sweeper.sweep("it-rewind", 100, r -> seen.add(r.getEventType())));
        assertEquals(2, seen.stream().filter("spine.test.replay"::equals).count());
    }

    @Transactional
    void rewind(String consumer) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("UPDATE core.event_consumer SET last_event_id = 0 WHERE consumer_name = :c")
          .setParameter("c", consumer).executeUpdate();
    }

    @Test
    void poisonPillDoesNotBlockTheCursor() {
        seed(1L, "spine.test.poison");
        seed(1L, "spine.test.after");
        List<String> seen = new CopyOnWriteArrayList<>();
        TenantContext.runAsSystem(() -> sweeper.sweep("it-poison", 100, r -> {
            if (r.getEventType().equals("spine.test.poison")) throw new IllegalStateException("boom");
            seen.add(r.getEventType());
        }));
        assertTrue(seen.contains("spine.test.after"));
        // cursor advanced past the poison row: a re-sweep must NOT redeliver it
        TenantContext.runAsSystem(() -> sweeper.sweep("it-poison", 100, r -> seen.add(r.getEventType())));
        assertEquals(1, seen.stream().filter("spine.test.after"::equals).count());
    }
}
