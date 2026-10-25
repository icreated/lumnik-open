package io.lumnik.hub.event.consumer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventHorizonWatch;
import io.lumnik.hub.event.EventOutboxRow;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// Runs with live schedulers on purpose: its it-* cursors are private, so the webhook/email ticks can't interfere — and it doubles as proof that consumers coexist.
@QuarkusTest
class EventSpineIT {

    @Inject OutboxSweeper sweeper;
    @Inject EntityManager em;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject EventHorizonWatch watch;

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

    /**
     * #1570: an event's id is assigned at INSERT and becomes visible at COMMIT. A transaction holding
     * the LOWER id commits after a sweep has read a HIGHER one: an id cursor has moved past it, and the
     * webhook, email and process consumers never read it. A cursor that reads only what is decided
     * reads both, each once.
     */
    @Test
    void anEventALongTransactionCommitsLateIsStillRead() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        try (Connection open = adminDs.getConnection()) {
            open.setAutoCommit(false);
            insert(open, "spine.test.late");                 // the lower id, not yet visible
            seed(1L, "spine.test.early");                     // the higher id, committed
            TenantContext.runAsSystem(() -> sweeper.sweep("it-late", 100, r -> seen.add(r.getEventType())));
            open.commit();                                    // the lower id becomes visible
        }
        TenantContext.runAsSystem(() -> sweeper.sweep("it-late", 100, r -> seen.add(r.getEventType())));

        assertEquals(List.of("spine.test.late", "spine.test.early"),
            seen.stream().filter(t -> t.startsWith("spine.test.")).toList(),
            "both events, each once, the late one included");
    }

    /**
     * #1570's price, said: while an older transaction stays open, newer events wait. The watch counts
     * the committed events that have waited past its patience — here one, made to look three minutes
     * old — and none once the blocker ends.
     */
    @Test
    void theWatchCountsEventsAnOpenTransactionHasHeldTooLong() throws Exception {
        try (Connection open = adminDs.getConnection()) {
            open.setAutoCommit(false);
            insert(open, "spine.test.blocker");               // the older transaction, still open
            try (Connection c = adminDs.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO core.event_outbox (tenant_id, event_type, source, payload, occurred_at, created_at) "
                     + "VALUES (1, 'spine.test.held', 'system', '{}'::jsonb, now(), now() - interval '3 minutes')")) {
                ps.executeUpdate();                            // newer, committed, held behind the blocker
            }
            assertEquals(1L, TenantContext.callAsSystem(watch::check));
            open.rollback();
        }
        assertEquals(0L, TenantContext.callAsSystem(watch::check));
    }

    private static void insert(Connection c, String type) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core.event_outbox (tenant_id, event_type, source, payload, occurred_at, created_at) "
                + "VALUES (1, ?, 'system', '{}'::jsonb, now(), now())")) {
            ps.setString(1, type);
            ps.executeUpdate();
        }
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
        em.createNativeQuery("UPDATE core.event_consumer SET last_xact_id = 0, last_event_id = 0 WHERE consumer_name = :c")
          .setParameter("c", consumer).executeUpdate();
    }

    @Transactional
    void seedBulk(int n) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.event_outbox (tenant_id, event_type, source, payload, occurred_at, created_at)
            SELECT 1, 'spine.test.bulk', 'system', '{}'::jsonb, NOW(), NOW() FROM generate_series(1, :n)
            """).setParameter("n", n).executeUpdate();
    }

    @Transactional
    long cursorOf(String consumer) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery("SELECT last_event_id FROM core.event_consumer WHERE consumer_name = :c")
            .setParameter("c", consumer).getSingleResult()).longValue();
    }

    @Transactional
    long maxBulkId() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery(
            "SELECT MAX(id) FROM core.event_outbox WHERE event_type = 'spine.test.bulk'").getSingleResult()).longValue();
    }

    /**
     * #943: a processor's failed DATABASE statement aborts the PostgreSQL transaction, so on the
     * batch's own transaction the sweep threw, the cursor rolled back and the same batch replayed
     * every tick. The statement now costs its event only.
     */
    @Test
    void aFailedStatementInOneEventCostsThatEventNotTheBatch() {
        seed(1L, "spine.test.ok1");
        seed(1L, "spine.test.dbpoison");
        seed(1L, "spine.test.ok2");
        List<String> seen = new CopyOnWriteArrayList<>();

        for (int sweep = 0; sweep < 3; sweep++) {
            TenantContext.runAsSystem(() -> sweeper.sweep("it-dbpoison", 100, r -> {
                seen.add(r.getEventType());
                if ("spine.test.dbpoison".equals(r.getEventType())) {
                    em.createNativeQuery("SELECT 1/0").getSingleResult();
                }
            }));
        }

        assertEquals(List.of("spine.test.ok1", "spine.test.dbpoison", "spine.test.ok2"), seen,
            "every event once: the poison one neither replays the batch nor hides the events after it");
        assertTrue(cursorOf("it-dbpoison") >= maxIdOf("spine.test.ok2"), "the cursor must stand past the batch");
    }

    /**
     * The price of the per-event transaction (#943): what a processor wrote for an event it then
     * threw on is rolled back with that event, while the events around it keep their writes and
     * the cursor still moves past it.
     */
    @Test
    void aProcessorThatWritesThenThrowsLosesThatEventsWritesOnly() {
        seed(1L, "spine.test.w1");
        seed(1L, "spine.test.wthrow");
        seed(1L, "spine.test.w2");

        TenantContext.runAsSystem(() -> sweeper.sweep("it-writethrow", 100, r -> {
            em.createNativeQuery("INSERT INTO core.event_outbox (tenant_id, event_type, source, payload, occurred_at, created_at) "
                + "VALUES (1, :t, 'system', '{}'::jsonb, NOW(), NOW())")
              .setParameter("t", "spine.test.mark." + r.getEventType()).executeUpdate();
            if ("spine.test.wthrow".equals(r.getEventType())) throw new IllegalStateException("after writing");
        }));

        assertEquals(1, countOf("spine.test.mark.spine.test.w1"));
        assertEquals(0, countOf("spine.test.mark.spine.test.wthrow"), "the failed event's write is rolled back");
        assertEquals(1, countOf("spine.test.mark.spine.test.w2"));
        assertTrue(cursorOf("it-writethrow") >= maxIdOf("spine.test.w2"), "the cursor still moves past it");
    }

    @Transactional
    long countOf(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM core.event_outbox WHERE event_type = :t")
            .setParameter("t", type).getSingleResult()).longValue();
    }

    @Transactional
    long maxIdOf(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM core.event_outbox WHERE event_type = :t")
            .setParameter("t", type).getSingleResult()).longValue();
    }

    @Test
    void oneTickDrainsTheBacklogBatchByBatchEachInItsOwnTransaction() {
        seedBulk(1000);
        AtomicInteger bulkSeen = new AtomicInteger();
        AtomicInteger batches = new AtomicInteger();
        List<Object> committedCursors = new CopyOnWriteArrayList<>();

        TenantContext.runAsSystem(() -> sweeper.drain("it-drain", 100, Duration.ofMinutes(1), () -> {
            batches.incrementAndGet();
            return r -> {
                if ("spine.test.bulk".equals(r.getEventType())) bulkSeen.incrementAndGet();
                // The cursor as COMMITTED: the batch in flight has not committed its own advance yet.
                em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
                committedCursors.add(em.createNativeQuery(
                    "SELECT last_event_id FROM core.event_consumer WHERE consumer_name = 'it-drain'")
                    .getResultList().stream().findFirst().orElse(-1L));
            };
        }));

        // One tick walks the whole backlog, not one batch of 100 per 5 s tick.
        assertEquals(1000, bulkSeen.get());
        assertTrue(cursorOf("it-drain") >= maxBulkId(), "the cursor must stand past the last seeded event");
        // ... and stops at the first batch that comes back short (here: the empty one after the last full batch).
        assertEquals(committedCursors.size() / 100 + 1, batches.get());
        // Every batch commits on its own: each batch starts from the cursor the one before it
        // committed, never from a single transaction around the loop (which would show one value).
        assertEquals((committedCursors.size() + 99) / 100, Set.copyOf(committedCursors).size());
    }

    @Test
    void aSpentBudgetEndsTheTickAfterOneBatch() {
        seedBulk(1000);
        AtomicInteger seen = new AtomicInteger();

        TenantContext.runAsSystem(() -> sweeper.drain("it-budget", 100, Duration.ZERO, () -> r -> seen.incrementAndGet()));

        // The first batch always runs; a spent budget starts no second one, however full the first came back.
        assertEquals(100, seen.get());
    }

    @Test
    void aFailedBatchEndsTheTickRatherThanReplayingWithinIt() {
        seed(1L, "spine.test.before");
        seed(1L, "spine.test.dbfail");
        List<String> seen = new CopyOnWriteArrayList<>();

        try {
            TenantContext.runAsSystem(() -> sweeper.drain("it-dbfail", 100, Duration.ofMinutes(1), () -> r -> {
                seen.add(r.getEventType());
                // A statement failing on the batch's own connection aborts the whole transaction:
                // today that batch rolls back and throws (#943).
                if ("spine.test.dbfail".equals(r.getEventType())) em.createNativeQuery("SELECT 1/0").getSingleResult();
            }));
        } catch (RuntimeException rolledBack) {
            // expected today; the assertion below holds either way
        }

        // Whatever the batch's fate, the tick must not run it again: a webhook consumer would
        // re-POST every event before the failure once per retry, as fast as the loop spins.
        assertEquals(1, seen.stream().filter("spine.test.before"::equals).count());
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
