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

import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
    void an_unchanged_row_keeps_its_tuple_and_a_changed_one_still_updates() {
        // #903: xmin is the transaction that wrote the tuple, so it only stays put when the
        // UPDATE of a row that came back identical was skipped.
        UUID connId = UUID.randomUUID();
        UUID epId   = UUID.randomUUID();
        String table = "ext.test_unchanged_" + System.nanoTime();
        HybridJsonbSpec spec = new HybridJsonbSpec(table, "$.id",
            List.of(new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty())));
        Map<String, Object> item = Map.of("id", "u1", "amount", 100, "note", "same");

        writeAsSystem(spec, item, "h-v1", connId, epId);
        String firstXmin = xminOf(table, "u1");
        writeAsSystem(spec, item, "h-v1", connId, epId);
        assertEquals(firstXmin, xminOf(table, "u1"), "an unchanged row must keep its tuple");

        writeAsSystem(spec, Map.of("id", "u1", "amount", 100, "note", "moved"), "h-v2", connId, epId);
        assertNotEquals(firstXmin, xminOf(table, "u1"), "a changed row must still be rewritten");
        assertEquals("moved", em.createNativeQuery(
            "SELECT data->>'note' FROM " + table + " WHERE external_id = 'u1'").getSingleResult());
    }

    @Test
    void a_promoted_column_added_later_fills_on_a_row_whose_content_did_not_change() {
        // docs/connectors/rest.md "Add a typed column later": the new column fills as rows are
        // read again. The payload — and so row_hash — is the same; only the spec moved. A skip
        // keyed on row_hash alone would leave the column empty for every unchanged row, forever.
        UUID connId = UUID.randomUUID();
        UUID epId   = UUID.randomUUID();
        String table = "ext.test_late_column_" + System.nanoTime();
        PromotedColumn amount = new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty());
        Map<String, Object> item = Map.of("id", "l1", "amount", 7, "currency", "eur");

        writeAsSystem(new HybridJsonbSpec(table, "$.id", List.of(amount)), item, "h-same", connId, epId);
        writeAsSystem(new HybridJsonbSpec(table, "$.id", List.of(amount,
                new PromotedColumn("currency", "$.currency", SqlType.TEXT, Optional.empty()))),
            item, "h-same", connId, epId);

        assertEquals("eur", em.createNativeQuery(
            "SELECT currency FROM " + table + " WHERE external_id = 'l1'").getSingleResult());
    }

    @Test
    void without_a_row_hash_a_conflicting_row_is_still_rewritten() {
        // Direct callers bind NULL hashes (the write overload without system contexts). NULL says
        // nothing about the content, so it must never read as "unchanged".
        UUID connId = UUID.randomUUID();
        UUID epId   = UUID.randomUUID();
        String table = "ext.test_no_hash_" + System.nanoTime();
        HybridJsonbSpec spec = new HybridJsonbSpec(table, "$.id",
            List.of(new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty())));

        writeAsSystem(spec, Map.of("id", "n1", "amount", 1, "note", "before"), null, connId, epId);
        writeAsSystem(spec, Map.of("id", "n1", "amount", 1, "note", "after"), null, connId, epId);

        assertEquals("after", em.createNativeQuery(
            "SELECT data->>'note' FROM " + table + " WHERE external_id = 'n1'").getSingleResult());
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

    /** One write in its own transaction; a null {@code rowHash} takes the overload without contexts. */
    private void writeAsSystem(HybridJsonbSpec spec, Map<String, Object> item, String rowHash,
                               UUID connId, UUID epId) {
        runInTxAsSystem(utx, () -> {
            if (rowHash == null) {
                writer.write(spec, List.of(item), 1L, connId, epId);
            } else {
                writer.write(spec, List.of(item), List.of(Map.of("_row_hash", rowHash)), 1L, connId, epId);
            }
        });
    }

    private String xminOf(String table, String externalId) {
        return (String) em.createNativeQuery(
                "SELECT xmin::text FROM " + table + " WHERE external_id = ?1")
            .setParameter(1, externalId)
            .getSingleResult();
    }
}
