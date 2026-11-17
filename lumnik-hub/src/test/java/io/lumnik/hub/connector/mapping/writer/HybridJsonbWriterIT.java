package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.testsupport.LockWaits;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class HybridJsonbWriterIT {

    @Inject HybridJsonbWriter writer;
    @Inject EntityManager em;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject DynamicTableRegistry registry;

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

    /** #1873: the drill-down from a citation looks a row up by {@code (tenant_id, row_hash)}; the table must serve it. */
    @Test
    void aNewTableCarriesTheRowHashIndexTheCitationLookupUses() throws Exception {
        String bare = "test_rh_new_" + System.nanoTime();
        writeOne("ext." + bare);

        Assertions.assertThat(rowHashIndexDef(bare)).contains("(tenant_id, row_hash)");
    }

    /** A table created before the index existed gets it on its next write, with no migration. */
    @Test
    void aTableCreatedBeforeTheIndexGetsItOnItsNextWrite() throws Exception {
        String bare = "test_rh_old_" + System.nanoTime();
        oldHybridTable(bare);
        Assertions.assertThat(rowHashIndexDef(bare)).as("an old table has no such index").isNull();

        writeOne("ext." + bare);

        Assertions.assertThat(rowHashIndexDef(bare)).contains("(tenant_id, row_hash)");
    }

    /** #2010: the hub creates a hybrid table and records it as its own in one transaction. */
    @Test
    void aNewTableIsRecordedAsTheHubsOwn() {
        String bare = "test_owned_" + System.nanoTime();
        writeOne("ext." + bare);

        Assertions.assertThat(owned(bare)).isTrue();
    }

    /**
     * #2010: the columns and the key are a shape any DDL can copy. A table the hub did not record is refused, whatever
     * it looks like, before any DDL touches it.
     */
    @Test
    void aTableWithTheHybridShapeTheHubDidNotRecordIsRefused() {
        String bare = "test_lookalike_" + System.nanoTime();
        Jdbc.update(adminDs, "create", HybridJsonbWriter.createTableSql("ext." + bare, List.of()), ps -> {});
        try {
            Assertions.assertThatThrownBy(() -> writeOne(new HybridJsonbSpec("ext." + bare, "$.id", List.of(
                    new PromotedColumn("grabbed", "$.x", SqlType.TEXT, Optional.empty()))), Map.of("id", "r_1", "x", "v")))
                .hasStackTraceContaining("ext." + bare + " is not a table this hub's writer made");
            Assertions.assertThat(Jdbc.query(adminDs, "columns", "SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = 'ext' AND table_name = ?", ps -> ps.setString(1, bare), rs -> rs.getString(1)))
                .as("no column was added").doesNotContain("grabbed");
            Assertions.assertThat(rowSecurity(bare)).as("no RLS was switched on").isFalse();
        } finally {
            Jdbc.update(adminDs, "drop", "DROP TABLE IF EXISTS ext." + bare, ps -> {});
        }
    }

    /** #2010: a table of the hub's that lost its isolation gets it back on the first write this process makes to it. */
    @Test
    void anOwnedTableThatLostItsIsolationGetsItBackOnItsFirstWrite() {
        String bare = "test_heal_" + System.nanoTime();
        writeOne("ext." + bare);
        Jdbc.update(adminDs, "unpolice", "DROP POLICY tenant_isolation ON ext." + bare, ps -> {});
        Jdbc.update(adminDs, "unsecure", "ALTER TABLE ext." + bare + " DISABLE ROW LEVEL SECURITY", ps -> {});
        writer.clearSchemaCache();

        writeOne("ext." + bare);

        Assertions.assertThat(rowSecurity(bare)).isTrue();
        Assertions.assertThat(policies(bare)).containsExactly("tenant_isolation");
    }

    /** #2010: a second permissive policy widens what RLS shows; the hub does not write through one it did not make. */
    @Test
    void anOwnedTableWithAnotherPermissivePolicyIsRefused() {
        String bare = "test_open_policy_" + System.nanoTime();
        writeOne("ext." + bare);
        Jdbc.update(adminDs, "open", "CREATE POLICY see_all ON ext." + bare + " USING (true)", ps -> {});
        writer.clearSchemaCache();

        Assertions.assertThatThrownBy(() -> writeOne("ext." + bare))
            .hasStackTraceContaining("ext." + bare + " carries the policy see_all");
    }

    /** #2010: a tenant_isolation that lets every row through is not the hub's; the first write puts the hub's back. */
    @Test
    void aTenantIsolationThatLetsEveryRowThroughIsReplaced() {
        String bare = "test_open_isolation_" + System.nanoTime();
        writeOne("ext." + bare);
        Jdbc.update(adminDs, "open", "ALTER POLICY tenant_isolation ON ext." + bare + " USING (true)", ps -> {});
        writer.clearSchemaCache();

        writeOne("ext." + bare);

        Assertions.assertThat(policyQual(bare)).isEqualTo(policyQual(freshTable()));
    }

    /**
     * #2010: the check compares the policy as Postgres renders it. A healthy table keeps the one it has: replacing it
     * would take the table's ACCESS EXCLUSIVE lock, readers included, at every restart.
     */
    @Test
    void aHealthyTableKeepsItsPolicy() {
        String bare = freshTable();
        long before = policyOid(bare);
        writer.clearSchemaCache();

        writeOne("ext." + bare);

        assertEquals(before, policyOid(bare));
    }

    /**
     * #2010: a healthy table is left as it is. Even a grant it already has rewrites its catalog row, which invalidates
     * every session's cached plans on it, at every restart.
     */
    @Test
    void aHealthyTableIsLeftAsItIs() {
        String bare = freshTable();
        String before = catalogRowVersion(bare);
        writer.clearSchemaCache();

        writeOne("ext." + bare);

        assertEquals(before, catalogRowVersion(bare));
    }

    /**
     * Postgres names a table by its first 63 characters, and its serial sequence by fewer: from 57 on, the sequence is
     * not {@code <table>_id_seq}. Both a new long table and one recorded before #2010 (by V98, under the name Postgres
     * kept) are the hub's.
     */
    @Test
    void aLongNamedTableIsMadeAndAdopted() {
        String fresh = ("test_long_new_" + System.nanoTime() + "_").repeat(3);
        writeOne("ext." + fresh);
        writer.clearSchemaCache();
        writeOne("ext." + fresh);

        String old = ("test_long_old_" + System.nanoTime() + "_").repeat(3);
        Jdbc.update(adminDs, "create", HybridJsonbWriter.createTableSql("ext." + old, List.of()), ps -> {});
        Jdbc.update(adminDs, "record", "INSERT INTO connector.hybrid_table (table_schema, table_name) "
            + "SELECT 'ext', relname FROM pg_class WHERE oid = to_regclass(?)", ps -> ps.setString(1, "ext." + old));
        writeOne("ext." + old);
    }

    /**
     * #2010: once this process has checked a table, a write takes the shared lock only and never waits for the writes in
     * flight, whose shared lock {@code inFlight} stands for: long names included (Postgres keeps a table's and a
     * column's first 63 characters, and the columns are read under those names), and after a write that failed on its
     * data.
     */
    @Test
    void aCheckedTableIsWrittenWithoutWaitingForTheWritesInFlight() throws Exception {
        String bare = ("test_warm_" + System.nanoTime() + "_").repeat(3);
        HybridJsonbSpec spec = new HybridJsonbSpec("ext." + bare, "$.id", List.of(
            new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty()),
            new PromotedColumn("amount_" + "x".repeat(70), "$.amount", SqlType.BIGINT, Optional.empty())));
        writeOne(spec, Map.of("id", "a", "amount", 1));
        writeOne(spec, Map.of("id", "b", "amount", 2));
        Assertions.assertThatThrownBy(() -> writeOne(spec, Map.of("amount", 3)))
            .hasStackTraceContaining("every item needs a key");

        try (Connection inFlight = adminDs.getConnection()) {
            DynamicTableWriter.writeLock(inFlight, "ext", bare);
            try {
                CompletableFuture.runAsync(() -> writeOne(spec, Map.of("id", "c", "amount", 4))).get(10, TimeUnit.SECONDS);
            } finally {
                DynamicTableWriter.releaseWriteLock(inFlight, "ext", bare);
            }
        }
    }

    /**
     * #2010: a table dropped behind this process's back (by another replica's connector delete) fails one write, which
     * forgets it: the next write checks it again and makes it.
     */
    @Test
    void aWriteThatFailsForgetsItsTable() {
        String bare = freshTable();
        Jdbc.update(adminDs, "drop", "DROP TABLE ext." + bare, ps -> {});
        Jdbc.update(adminDs, "forget", "DELETE FROM connector.hybrid_table WHERE table_schema = 'ext' AND table_name = ?",
            ps -> ps.setString(1, bare));

        Assertions.assertThatThrownBy(() -> writeOne("ext." + bare));
        writeOne("ext." + bare);

        Assertions.assertThat(owned(bare)).isTrue();
    }

    /** A new table of the hub's, written once. */
    private String freshTable() {
        String bare = "test_fresh_" + System.nanoTime();
        writeOne("ext." + bare);
        return bare;
    }

    private String catalogRowVersion(String bare) {
        return Jdbc.queryOne(adminDs, "xmin", "SELECT xmin::text FROM pg_class WHERE oid = to_regclass(?)",
            ps -> ps.setString(1, "ext." + bare), rs -> rs.getString(1)).orElseThrow();
    }

    /**
     * #2010: the table, its isolation, its grants and its record land together or not at all. Here the hub dies between
     * them: the record's insert waits on a lock, and its session is ended. The table goes with it, and the next write
     * makes it whole.
     */
    @Test
    void aCreationInterruptedBeforeItsRecordLeavesNoTable() throws Exception {
        String bare = "test_atomic_" + System.nanoTime();
        try (Connection other = adminDs.getConnection()) {
            other.setAutoCommit(false);
            try (var st = other.createStatement()) {
                st.execute("LOCK TABLE connector.hybrid_table IN EXCLUSIVE MODE");
            }
            CompletableFuture<Void> write = CompletableFuture.runAsync(() -> writeOne("ext." + bare));
            int waiting = LockWaits.awaitBlockedBy(adminDs, other);
            Jdbc.queryOne(adminDs, "kill", "SELECT pg_terminate_backend(?)", ps -> ps.setInt(1, waiting),
                rs -> rs.getBoolean(1));
            Assertions.assertThatThrownBy(() -> write.get(30, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            other.rollback();
        }
        Assertions.assertThat(Jdbc.queryOne(adminDs, "exists", "SELECT to_regclass(?) IS NOT NULL",
            ps -> ps.setString(1, "ext." + bare), rs -> rs.getBoolean(1))).contains(false);
        Assertions.assertThat(owned(bare)).isFalse();

        writeOne("ext." + bare);
        Assertions.assertThat(rowSecurity(bare)).isTrue();
        Assertions.assertThat(owned(bare)).isTrue();
    }

    /** #2010: two first writes of one new table, as two replicas make them: one creates it, the other finds it whole. */
    @Test
    void twoFirstWritesOfOneNewTableBothLand() throws Exception {
        for (int round = 0; round < 5; round++) {
            String table = "ext.test_twin_" + System.nanoTime();
            writeAtOnce(table, table);
            assertEquals(2L, ((Number) em.createNativeQuery("SELECT count(*) FROM " + table).getSingleResult()).longValue());
        }
    }

    /** #2010: two new tables of one schema, made at once: both grant on the schema, whose catalog row they share. */
    @Test
    void twoNewTablesOfOneSchemaMadeAtOnceBothLand() throws Exception {
        for (int round = 0; round < 5; round++) {
            String first = "ext.test_pair_a_" + System.nanoTime();
            String second = "ext.test_pair_b_" + System.nanoTime();
            writeAtOnce(first, second);
            assertTrue(owned(first.substring(4)) && owned(second.substring(4)));
        }
    }

    /** A first write into each table, both at once, as two replicas make them. */
    private void writeAtOnce(String first, String second) throws Exception {
        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> writeAsSystem(new HybridJsonbSpec(first, "$.id",
            List.of()), Map.of("id", "a"), null, UUID.randomUUID(), UUID.randomUUID()));
        CompletableFuture<Void> b = CompletableFuture.runAsync(() -> writeAsSystem(new HybridJsonbSpec(second, "$.id",
            List.of()), Map.of("id", "b"), null, UUID.randomUUID(), UUID.randomUUID()));
        CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);
    }

    /** A hybrid table made before #2010, recorded as V98 records it. */
    private void oldHybridTable(String bare) {
        Jdbc.update(adminDs, "create", HybridJsonbWriter.createTableSql("ext." + bare, List.of()), ps -> {});
        Jdbc.update(adminDs, "record", "INSERT INTO connector.hybrid_table (table_schema, table_name) VALUES ('ext', ?)",
            ps -> ps.setString(1, bare));
    }

    private boolean owned(String bare) {
        return Jdbc.exists(adminDs, "owned", "SELECT 1 FROM connector.hybrid_table WHERE table_schema = 'ext' "
            + "AND table_name = ?", ps -> ps.setString(1, bare));
    }

    private boolean rowSecurity(String bare) {
        return Jdbc.queryOne(adminDs, "rls", "SELECT relrowsecurity FROM pg_class WHERE oid = to_regclass(?)",
            ps -> ps.setString(1, "ext." + bare), rs -> rs.getBoolean(1)).orElseThrow();
    }

    private List<String> policies(String bare) {
        return Jdbc.query(adminDs, "policies", "SELECT polname FROM pg_policy WHERE polrelid = to_regclass(?)",
            ps -> ps.setString(1, "ext." + bare), rs -> rs.getString(1));
    }

    private String policyQual(String bare) {
        return Jdbc.queryOne(adminDs, "qual", "SELECT pg_get_expr(polqual, polrelid) FROM pg_policy "
            + "WHERE polname = 'tenant_isolation' AND polrelid = to_regclass(?)", ps -> ps.setString(1, "ext." + bare),
            rs -> rs.getString(1)).orElseThrow();
    }

    private long policyOid(String bare) {
        return Jdbc.queryOne(adminDs, "oid", "SELECT oid FROM pg_policy WHERE polname = 'tenant_isolation' "
            + "AND polrelid = to_regclass(?)", ps -> ps.setString(1, "ext." + bare), rs -> rs.getLong(1)).orElseThrow();
    }

    /**
     * #1989: under {@code connector} sit the hub's own tables (connector.connector, the t_* of DynamicTableWriter, the
     * entity views), and a target may name one. The writer refuses a table it did not make before any DDL touches it:
     * no promoted column, no row_hash, no index — and says why, by name.
     */
    @Test
    void anExistingTableThatIsNotAHybridOneIsRefusedBeforeAnyDdl() throws Exception {
        String bare = "test_foreign_" + System.nanoTime();
        try (Connection c = adminDs.getConnection(); var st = c.createStatement()) {
            st.execute("CREATE TABLE connector." + bare + " (id BIGINT, tenant_id BIGINT, name TEXT)");
        }
        HybridJsonbSpec spec = new HybridJsonbSpec("connector." + bare, "$.id",
            List.of(new PromotedColumn("grabbed", "$.x", SqlType.TEXT, Optional.empty())));

        try {
            Assertions.assertThatThrownBy(() -> writeOne(spec, Map.of("id", "r_1", "x", "v")))
                .hasStackTraceContaining("connector." + bare + " is not a table this hub's writer made");

            Assertions.assertThat(Jdbc.query(adminDs, "columns", "SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = 'connector' AND table_name = ? ORDER BY ordinal_position",
                    ps -> ps.setString(1, bare), rs -> rs.getString(1)))
                .as("no column was added").containsExactly("id", "tenant_id", "name");
            Assertions.assertThat(Jdbc.queryOne(adminDs, "indexes",
                    "SELECT count(*) FROM pg_indexes WHERE schemaname = 'connector' AND tablename = ?",
                    ps -> ps.setString(1, bare), rs -> rs.getLong(1)))
                .as("no index was made").contains(0L);
        } finally {
            Jdbc.update(adminDs, "drop", "DROP TABLE IF EXISTS connector." + bare, ps -> {});
        }
    }

    /**
     * The columns alone do not make a hybrid table: a connector's t_* table may carry a source column named external_id
     * or data, and declare a unique INDEX over (tenant_id, external_id). The writer's key is the table CONSTRAINT it
     * creates, which a connector never declares.
     */
    @Test
    void aTableWithTheHybridColumnsButNotItsKeyIsRefusedBeforeAnyDdl() {
        String bare = "t_lookalike_" + System.nanoTime();
        Jdbc.update(adminDs, "create", "CREATE TABLE connector." + bare + " (id BIGINT, tenant_id BIGINT, external_id TEXT,"
            + " data JSONB, connector_id UUID, endpoint_id UUID)", ps -> {});
        try {
            Jdbc.update(adminDs, "index", "CREATE UNIQUE INDEX " + bare + "_k ON connector." + bare
                + " (tenant_id, external_id)", ps -> {});
            Assertions.assertThatThrownBy(() -> writeOne(new HybridJsonbSpec("connector." + bare, "$.id", List.of(
                    new PromotedColumn("grabbed", "$.x", SqlType.TEXT, Optional.empty()))), Map.of("id", "r_1", "x", "v")))
                .hasStackTraceContaining("connector." + bare + " is not a table this hub's writer made");
            Assertions.assertThat(Jdbc.query(adminDs, "columns", "SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = 'connector' AND table_name = ?", ps -> ps.setString(1, bare), rs -> rs.getString(1)))
                .as("no column was added").doesNotContain("grabbed", "row_hash");
        } finally {
            Jdbc.update(adminDs, "drop", "DROP TABLE IF EXISTS connector." + bare, ps -> {});
        }
    }

    /**
     * #2048: an entity reads {@code connector} tables, and its scope checks ask the registry who writes them. A writer
     * whose registration fails would write rows no check sees, so there the write stops before any row. The writer
     * here has no connector row: the registry's foreign key refuses it.
     */
    @Test
    void aConnectorTableWhoseWriterCannotBeRegisteredGetsNoRow() {
        String bare = "test_unreg_" + System.nanoTime();
        try {
            Assertions.assertThatThrownBy(() -> writeOne("connector." + bare))
                .hasStackTraceContaining("its writer could not be recorded, so no row is written");
            // The table is made, and committed, before its writer is recorded: it is there, empty.
            Assertions.assertThat(Jdbc.queryOne(adminDs, "rows", "SELECT count(*) FROM connector." + bare,
                    ps -> {}, rs -> rs.getLong(1)))
                .as("no row was written").contains(0L);
        } finally {
            Jdbc.update(adminDs, "drop", "DROP TABLE IF EXISTS connector." + bare, ps -> {});
            Jdbc.update(adminDs, "forget", "DELETE FROM connector.hybrid_table WHERE table_schema = 'connector' "
                + "AND table_name = ?", ps -> ps.setString(1, bare));
        }
    }

    private void writeOne(String table) {
        writeOne(new HybridJsonbSpec(table, "$.id", List.of()), Map.of("id", "r_1"));
    }

    private void writeOne(HybridJsonbSpec spec, Map<String, Object> item) {
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                writer.write(spec, List.of(item), 1L, UUID.randomUUID(), UUID.randomUUID());
                utx.commit();
            } catch (Exception e) {
                try { utx.rollback(); } catch (Exception ignored) { /* no transaction left to roll back */ }
                throw new RuntimeException(e);
            }
        });
    }

    private String rowHashIndexDef(String bare) throws SQLException {
        try (Connection c = adminDs.getConnection();
             var ps = c.prepareStatement("SELECT indexdef FROM pg_indexes WHERE schemaname = 'ext' AND tablename = ?"
                 + " AND indexdef LIKE '%row_hash%'")) {
            ps.setString(1, bare);
            try (var rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
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
    void a_promoted_value_reaches_its_type_or_is_one_cell_error_never_a_failed_write() {
        // #1398: a JSON API sends instants and numbers as strings; bound as varchar they failed every run.
        String table = "ext.test_typed_" + System.nanoTime();
        HybridJsonbSpec spec = new HybridJsonbSpec(table, "$.id", List.of(
            new PromotedColumn("updated_at", "$.updated_at", SqlType.TIMESTAMPTZ, Optional.empty()),
            new PromotedColumn("qty", "$.qty", SqlType.BIGINT, Optional.empty())));
        long[] unreadable = new long[1];
        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                unreadable[0] = writer.write(spec, List.of(
                    Map.of("id", "o1", "updated_at", "2026-10-01T08:01:00.000Z", "qty", "12"),
                    Map.of("id", "o2", "updated_at", "yesterday", "qty", "3")), 1L, UUID.randomUUID(), UUID.randomUUID());
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals(1L, unreadable[0]);
        Object[] o1 = (Object[]) em.createNativeQuery(
            "SELECT updated_at IS NOT NULL, qty FROM " + table + " WHERE external_id = 'o1'").getSingleResult();
        assertEquals(true, o1[0]);
        assertEquals(12L, ((Number) o1[1]).longValue());
        Object[] o2 = (Object[]) em.createNativeQuery(
            "SELECT updated_at IS NULL, data->>'updated_at' FROM " + table + " WHERE external_id = 'o2'").getSingleResult();
        assertEquals(true, o2[0]);
        assertEquals("yesterday", o2[1], "the payload keeps the value whole");
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

    @Test
    void a_second_connector_writing_the_same_table_registers_it_too() {
        // #1186: each writer's registration is what its delete and its scope check read
        String table = "ext.test_two_writers_" + System.nanoTime();
        HybridJsonbSpec spec = new HybridJsonbSpec(table, "$.id", List.of());
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        runInTxAsSystem(utx, () -> {
            for (UUID id : List.of(first, second)) {
                em.createNativeQuery("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, enabled) "
                        + "VALUES (?1, 1, ?2, 'rest-generic', '{}'::jsonb, true)")
                    .setParameter(1, id).setParameter(2, "two-writers-" + id).executeUpdate();
            }
        });

        writeAsSystem(spec, Map.of("id", "a"), null, first, UUID.randomUUID());
        writeAsSystem(spec, Map.of("id", "b"), null, second, UUID.randomUUID());

        Number registrations = (Number) em.createNativeQuery(
                "SELECT count(*) FROM connector.dynamic_table WHERE table_schema = 'ext' AND table_name = ?1")
            .setParameter(1, table.substring("ext.".length()))
            .getSingleResult();
        assertEquals(2, registrations.intValue());
    }

    @Test
    void a_write_waits_while_a_delete_decides_whether_to_drop_its_table() throws Exception {
        // #1200 review: a delete checks "no other writer" then drops; a write landing in between was lost
        String bare = "test_drop_race_" + System.nanoTime();
        HybridJsonbSpec spec = new HybridJsonbSpec("ext." + bare, "$.id", List.of());
        writeAsSystem(spec, Map.of("id", "first"), null, UUID.randomUUID(), UUID.randomUUID());

        try (Connection delete = adminDs.getConnection()) {
            delete.setAutoCommit(false);
            DynamicTableWriter.firstWriteLock(delete, "ext", bare);   // what the purge holds while it decides
            CompletableFuture<Void> write = CompletableFuture.runAsync(() ->
                writeAsSystem(spec, Map.of("id", "second"), null, UUID.randomUUID(), UUID.randomUUID()));
            Thread.sleep(1000);
            assertFalse(write.isDone(), "the write must wait for the delete's decision");
            delete.commit();
            write.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void a_delete_of_the_connector_writing_resolves_instead_of_hanging() throws Exception {
        // #1200 review: the delete holds the connector's registry rows, then waits for the write's lock;
        // the write, holding that lock, upserts the same registry row. Postgres must see the cycle.
        String bare = "test_drop_cycle_" + System.nanoTime();
        HybridJsonbSpec spec = new HybridJsonbSpec("ext." + bare, "$.id", List.of());
        UUID writer = UUID.randomUUID();
        runInTxAsSystem(utx, () -> em.createNativeQuery("INSERT INTO connector.connector "
                + "(id, tenant_id, name, type_id, config, enabled) VALUES (?1, 1, ?2, 'rest-generic', '{}'::jsonb, true)")
            .setParameter(1, writer).setParameter(2, "cycle-" + writer).executeUpdate());
        registry.register(1L, "hybrid:ext." + bare, "ext", bare, Map.of("data", "JSONB"), writer);

        try (Connection delete = adminDs.getConnection()) {
            delete.setAutoCommit(false);
            try (var st = delete.createStatement()) {
                st.execute("SET LOCAL lock_timeout = '8s'");   // without the fix the cycle would never end
                st.execute("DELETE FROM connector.dynamic_table WHERE connector_id = '" + writer + "'");
            }
            CompletableFuture<Void> write = CompletableFuture.runAsync(() ->
                writeAsSystem(spec, Map.of("id", "a"), null, writer, UUID.randomUUID()));
            Thread.sleep(1000);   // the write now holds the lock and waits on the deleted registry row
            long start = System.nanoTime();
            try {
                DynamicTableWriter.firstWriteLock(delete, "ext", bare);
                delete.commit();
            } catch (SQLException deadlockOrTimeout) {
                delete.rollback();
            }
            write.get(15, TimeUnit.SECONDS);
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5,
                "Postgres resolved the cycle (deadlock_timeout), rather than the 8s lock_timeout");
        }
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
