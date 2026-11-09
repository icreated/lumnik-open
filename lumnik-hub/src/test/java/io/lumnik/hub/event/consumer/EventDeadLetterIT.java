package io.lumnik.hub.event.consumer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** #1837: what a consumer throws on is kept in core.event_dead_letter, in the transaction that advances the cursor. */
@QuarkusTest
class EventDeadLetterIT {

    @Inject OutboxSweeper sweeper;
    @Inject EntityManager em;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @BeforeEach
    @Transactional
    void clean() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_dead_letter WHERE consumer_name LIKE 'it-%'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_outbox").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_consumer WHERE consumer_name LIKE 'it-%'").executeUpdate();
    }

    @Transactional
    void seed(long tenantId, String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.persist(EventOutboxRow.of(tenantId, type, Map.of("k", "v")));
    }

    private void awaitDecided() {
        await().atMost(60, SECONDS).pollInterval(25, MILLISECONDS).until(() -> scalar(
            "SELECT count(*) FROM core.event_outbox WHERE NOT (" + EventOutboxRow.DECIDED + ")") == 0);
    }

    private long scalar(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> deadLetters(String consumer) {
        List<String> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT event_id, tenant_id, target_key, error, envelope->>'event_type' "
                 + "FROM core.event_dead_letter WHERE consumer_name = '" + consumer + "' ORDER BY event_id")) {
            while (rs.next()) {
                out.add(rs.getLong(1) + "|" + rs.getLong(2) + "|" + rs.getString(3) + "|" + rs.getString(4) + "|" + rs.getString(5));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private long idOf(String type) {
        return scalar("SELECT MAX(id) FROM core.event_outbox WHERE event_type = '" + type + "'");
    }

    @Test
    void anEventAConsumerThrowsOnIsKeptWithItsEnvelopeAndTheCursorMovesOn() {
        seed(7L, "dlq.test.ok1");
        seed(7L, "dlq.test.boom");
        seed(7L, "dlq.test.ok2");
        awaitDecided();
        List<String> seen = new ArrayList<>();

        TenantContext.runAsSystem(() -> sweeper.sweep("it-dlq", 100, r -> {
            seen.add(r.getEventType());
            if ("dlq.test.boom".equals(r.getEventType())) throw new IllegalStateException("secret row 42 in the message");
        }));

        assertEquals(List.of("dlq.test.ok1", "dlq.test.boom", "dlq.test.ok2"), seen, "the cursor never blocks");
        assertEquals(List.of(idOf("dlq.test.boom") + "|7||IllegalStateException|dlq.test.boom"), deadLetters("it-dlq"),
            "one row for the event: its tenant, no target, the exception's class and a copy of the envelope");
    }

    @Test
    void aFailedStatementIsKeptToo() {
        seed(7L, "dlq.test.dbpoison");
        awaitDecided();

        TenantContext.runAsSystem(() -> sweeper.sweep("it-dlq", 100, r -> em.createNativeQuery("SELECT 1/0").getSingleResult()));

        List<String> kept = deadLetters("it-dlq");
        assertEquals(1, kept.size());
        assertTrue(kept.get(0).contains("|SQLSTATE 22012|"), "the SQLSTATE, not the driver's text: " + kept.get(0));
    }

    @Test
    void theSameEventFailingAgainIsOneRowStillToLookAt() {
        seed(7L, "dlq.test.again");
        awaitDecided();
        TenantContext.runAsSystem(() -> sweeper.sweep("it-dlq", 100, r -> { throw new IllegalStateException("1"); }));
        markReplayed("it-dlq");
        rewind("it-dlq");

        TenantContext.runAsSystem(() -> sweeper.sweep("it-dlq", 100, r -> { throw new IllegalStateException("2"); }));

        assertEquals(1, deadLetters("it-dlq").size(), "unique per (consumer, event, target)");
        assertEquals(0, scalar("SELECT count(*) FROM core.event_dead_letter WHERE consumer_name = 'it-dlq' AND replayed_at IS NOT NULL"),
            "a new failure reopens the row");
    }

    @Test
    void anEventThatSucceedsLeavesNothing() {
        seed(7L, "dlq.test.fine");
        awaitDecided();

        TenantContext.runAsSystem(() -> sweeper.sweep("it-dlq", 100, r -> {}));

        assertEquals(List.of(), deadLetters("it-dlq"));
    }

    @Transactional
    void markReplayed(String consumer) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("UPDATE core.event_dead_letter SET replayed_at = now() WHERE consumer_name = :c")
          .setParameter("c", consumer).executeUpdate();
    }

    @Transactional
    void rewind(String consumer) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("UPDATE core.event_consumer SET last_event_id = 0, last_xact_id = 0 WHERE consumer_name = :c")
          .setParameter("c", consumer).executeUpdate();
    }
}
