package io.lumnik.hub.event;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards V24's occurred_at backfill semantics. A Flyway migration runs at boot against
 * a fresh container with no historical rows, so the backfill can't be observed on the
 * real table — instead this replays V24's transform (ADD COLUMN occurred_at NOT NULL
 * DEFAULT NOW(), then backfill) on a temp fixture carrying past created_at values.
 *
 * If the V24 transform here drifts from the migration file, update both together.
 */
@QuarkusTest
class OccurredAtBackfillIT {

    @Inject EntityManager em;

    @Test
    @Transactional
    void backfillStampsHistoricalRowsWithCreatedAtNotMigrationTime() {
        // Pre-V24 shape: created_at only, two rows whose real occurrence is in the past.
        // ON COMMIT DROP: temp tables are session- (connection-) scoped, so without it the
        // table would survive on the pooled connection and collide with a later test's CREATE.
        em.createNativeQuery(
            "CREATE TEMP TABLE t_evt (id bigserial PRIMARY KEY, created_at timestamptz NOT NULL) ON COMMIT DROP")
          .executeUpdate();
        em.createNativeQuery(
            "INSERT INTO t_evt (created_at) VALUES (NOW() - INTERVAL '90 days'), (NOW() - INTERVAL '1 day')")
          .executeUpdate();

        // V24 transform — must mirror V24__events_spine.sql section 4 + the backfill.
        em.createNativeQuery(
            "ALTER TABLE t_evt ADD COLUMN occurred_at timestamptz NOT NULL DEFAULT NOW()")
          .executeUpdate();
        em.createNativeQuery("UPDATE t_evt SET occurred_at = created_at").executeUpdate();

        long mismatched = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM t_evt WHERE occurred_at <> created_at").getSingleResult()).longValue();
        assertEquals(0, mismatched, "occurred_at must equal created_at for historical rows");

        long stampedAtMigrationTime = ((Number) em.createNativeQuery(
            "SELECT count(*) FROM t_evt WHERE occurred_at > NOW() - INTERVAL '1 minute'")
            .getSingleResult()).longValue();
        assertEquals(0, stampedAtMigrationTime,
            "no historical row may carry the migration timestamp as its occurred_at");
    }
}
