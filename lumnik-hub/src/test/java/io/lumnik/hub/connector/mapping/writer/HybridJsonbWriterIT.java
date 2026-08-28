package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class HybridJsonbWriterIT {

    @Inject HybridJsonbWriter writer;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    // Registry case: same V21-guard pin as DynamicTableWriterIT — ext.* policies must be
    // born with NULLIF (empty GUC → zero rows, never 22P02).
    @Test
    void createdPolicy_carriesTheV21NullifGuard() throws Exception {
        String bare = "test_guard_" + System.nanoTime();
        HybridJsonbSpec spec = new HybridJsonbSpec("ext." + bare, "$.id", List.of());
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                writer.write(spec, List.of(Map.of("id", "g_1")), 1L, UUID.randomUUID(), UUID.randomUUID());
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        String qual = (String) em.createNativeQuery(
                "SELECT qual FROM pg_policies WHERE policyname = 'tenant_isolation' "
                + "AND schemaname = 'ext' AND tablename = ?1")
            .setParameter(1, bare)
            .getSingleResult();
        Assertions.assertThat(qual)
            .as("the V21 guard must be born with the table").contains("NULLIF");
    }

    @Test
    void auto_creates_table_then_upserts_with_conflict() throws Exception {
        UUID connId = UUID.randomUUID();
        UUID epId   = UUID.randomUUID();
        // Use a unique table name per test run to avoid cross-test collisions
        String table = "ext.test_charges_" + System.nanoTime();

        HybridJsonbSpec spec = new HybridJsonbSpec(
            table,
            "$.id",
            List.of(
                new PromotedColumn("amount",   "$.amount",   SqlType.BIGINT, Optional.empty()),
                new PromotedColumn("currency", "$.currency", SqlType.TEXT,   Optional.empty())
            )
        );

        Map<String, Object> item1 = Map.of("id", "ch_1", "amount", 1099, "currency", "usd",
                                           "description", "Order 42");

        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                writer.write(spec, List.of(item1), 1L, connId, epId);
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Number count = (Number) em.createNativeQuery(
            "SELECT count(*) FROM " + table + " WHERE external_id = 'ch_1'"
        ).getSingleResult();
        assertEquals(1L, count.longValue());

        // UPSERT — same id, new amount
        Map<String, Object> item1Updated = Map.of("id", "ch_1", "amount", 2000, "currency", "usd");
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                writer.write(spec, List.of(item1Updated), 1L, connId, epId);
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Number amt = (Number) em.createNativeQuery(
            "SELECT amount FROM " + table + " WHERE external_id = 'ch_1'"
        ).getSingleResult();
        assertEquals(2000L, amt.longValue());

        Number total = (Number) em.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult();
        assertEquals(1L, total.longValue());  // no duplicate
    }

    @Test
    void system_context_row_hash_is_persisted_and_upserted() throws Exception {
        // C4/P18 PR1: the hash the hybrid branch used to throw away is the row identity the
        // event emission (PR2) will key on — it must survive the write AND follow the upsert.
        UUID connId = UUID.randomUUID();
        UUID epId   = UUID.randomUUID();
        String table = "ext.test_rowhash_" + System.nanoTime();
        HybridJsonbSpec spec = new HybridJsonbSpec(table, "$.id",
            List.of(new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty())));

        Map<String, Object> item = Map.of("id", "ch_9", "amount", 100);
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                writer.write(spec, List.of(item), List.of(Map.of("_row_hash", "h-v1")), 1L, connId, epId);
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals("h-v1", em.createNativeQuery(
            "SELECT row_hash FROM " + table + " WHERE external_id = 'ch_9'").getSingleResult());

        Map<String, Object> changed = Map.of("id", "ch_9", "amount", 200);
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                writer.write(spec, List.of(changed), List.of(Map.of("_row_hash", "h-v2")), 1L, connId, epId);
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals("h-v2", em.createNativeQuery(
            "SELECT row_hash FROM " + table + " WHERE external_id = 'ch_9'").getSingleResult());
    }

    @Test
    void retrofit_adds_row_hash_to_a_pre_existing_table_and_populates_it() throws Exception {
        // Upgrade path: a table created before C4/P18 PR1 has no row_hash column. The next
        // write must add it (ensureTable retrofit) and populate it — PR2's change detection
        // will depend on this column existing on every ext.* table, old or new.
        UUID connId = UUID.randomUUID();
        UUID epId   = UUID.randomUUID();
        String table = "ext.test_retrofit_" + System.nanoTime();
        HybridJsonbSpec spec = new HybridJsonbSpec(table, "$.id",
            List.of(new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty())));

        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                writer.write(spec, List.of(Map.of("id", "r1", "amount", 1)), 1L, connId, epId);
                utx.commit();
                // Reshape to the pre-PR world: no row_hash column, cold cache.
                utx.begin();
                em.createNativeQuery("ALTER TABLE " + table + " DROP COLUMN row_hash").executeUpdate();
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        writer.clearSchemaCache();

        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                writer.write(spec, List.of(Map.of("id", "r2", "amount", 2)),
                    List.of(Map.of("_row_hash", "h-retro")), 1L, connId, epId);
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        assertEquals("h-retro", em.createNativeQuery(
            "SELECT row_hash FROM " + table + " WHERE external_id = 'r2'").getSingleResult());
    }

    @Test
    void drift_adds_missing_column() throws Exception {
        UUID connId = UUID.randomUUID();
        UUID epId   = UUID.randomUUID();
        String table = "ext.test_drift_" + System.nanoTime();

        // V1: create table with only "amount" promoted
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                HybridJsonbSpec specA = new HybridJsonbSpec(table, "$.id",
                    List.of(new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty())));
                writer.write(specA, List.of(Map.of("id", "d_1", "amount", 100)), 1L, connId, epId);
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        // Force cache re-read so the next write sees only the v1 column set
        writer.clearSchemaCache();

        // V2: spec adds "currency" — writer must ALTER TABLE to add it
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                HybridJsonbSpec specB = new HybridJsonbSpec(table, "$.id",
                    List.of(
                        new PromotedColumn("amount",   "$.amount",   SqlType.BIGINT, Optional.empty()),
                        new PromotedColumn("currency", "$.currency", SqlType.TEXT,   Optional.empty())));
                writer.write(specB, List.of(Map.of("id", "d_2", "amount", 200, "currency", "eur")), 1L, connId, epId);

                String result = (String) em.createNativeQuery(
                    "SELECT currency FROM " + table + " WHERE external_id = 'd_2'"
                ).getSingleResult();
                assertEquals("eur", result);
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
