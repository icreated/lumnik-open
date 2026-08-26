package io.lumnik.hub.event;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards V24's webhook cutover-seed semantics: a migrated DB must not replay any
 * historical event to webhooks. A cursor is a single watermark, so the only coherent
 * seed is "pin past every existing row" — no row may remain above the cursor. Replays
 * V24's seed on a temp fixture carrying the legacy interleaving of published/pending rows.
 *
 * If the V24 seed here drifts from the migration file, update both together.
 */
@QuarkusTest
class WebhookCutoverSeedIT {

    @Inject EntityManager em;

    @Test
    @Transactional
    void cutoverSeedLeavesNoHistoricalRowAboveTheCursor() {
        em.createNativeQuery(
            "CREATE TEMP TABLE t_evt (id bigserial PRIMARY KEY, published_at timestamptz) ON COMMIT DROP")
          .executeUpdate();
        // Legacy interleaving: two delivered (published) rows, then a never-delivered
        // pending tail (id 3) — exactly the case a published-only MAX would mis-handle.
        em.createNativeQuery("INSERT INTO t_evt (published_at) VALUES (NOW()), (NOW()), (NULL)")
          .executeUpdate();

        // V24 cutover seed — must mirror V24__events_spine.sql section 2.
        long seed = ((Number) em.createNativeQuery(
            "SELECT COALESCE(MAX(id), 0) FROM t_evt").getSingleResult()).longValue();

        long aboveCursor = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM t_evt WHERE id > :s").setParameter("s", seed).getSingleResult()).longValue();
        assertEquals(0, aboveCursor,
            "no historical row may sit above the cutover cursor — a migrated DB must not replay any event");
    }
}
