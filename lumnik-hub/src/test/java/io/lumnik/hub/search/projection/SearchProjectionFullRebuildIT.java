package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.matchEntityYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.replaceViewAsAnOlderHubCompiledIt;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.workerWith;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code @QuarkusTest} twin of {@code SearchProjectionWorkerRebuildTest}, proving §5b's real
 * cursor/transaction mechanics against a genuine Postgres — spec tests 9's twin, 11, 20 and 26 (design
 * doc, Task 12 brief). {@code SearchProjectionWorkerRebuildTest} covers the mocked failure path;
 * everything here needs a real view, a real {@code core.search_index}/{@code
 * core.search_projection_state}/{@code core.search_projection_mark} and a real transaction.
 *
 * <p>The {@link ProjectionSink} under test is an in-memory {@link RecordingSink} substituted by hand
 * for the real, CDI-resolved one via {@code SearchProjectionItSupport.workerWith}: {@link
 * SearchProjectionWorker} is built directly with {@code new} (its own package-private, multi-arg
 * test constructor — the same one {@code SearchProjectionWorkerRebuildTest} and {@code
 * SearchProjectionWorkerTest} already use), and its {@code adminDs}/{@code readonlyDs}/{@code
 * registry} fields — package-private, same package as this test — are set to the real, CDI-injected
 * beans afterward. No CDI alternative/{@code @Priority} bean is needed: {@code ProjectionSinkGate} is
 * a plain class, subclassed there to return the fake sink directly, exactly the {@code FakeGate}
 * idiom {@code SearchProjectionWorkerTest} already uses.
 */
@QuarkusTest
class SearchProjectionFullRebuildIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;

    // -- match: mode fixtures (code/name/city) -------------------------------------------------

    private void createMatchTable(String table) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS connector." + table);
            st.execute("CREATE TABLE connector." + table + " (tenant_id bigint NOT NULL, "
                + "src_code text, src_name text, src_city text)");
            st.execute("ALTER TABLE connector." + table + " ENABLE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY " + table + "_iso ON connector." + table
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            // security_invoker views (EntityViewCompiler) check the QUERYING role's own grants on the
            // base table, not the view owner's — the full rebuild scan runs against the "readonly"
            // datasource, so that role needs its own SELECT here too, exactly like lumnik_app
            // (MatchKeyEncodingViewParityIT, EntityRagChatIT already establish this grant pair for a
            // hand-made test table). Guarded like EntityRagChatIT's own: under plain @QuarkusTest
            // (no RlsEnforcedProfile) the "readonly" datasource is aliased to lumnik_app itself and
            // the lumnik_readonly role does not exist at all, so a bare GRANT would fail the fixture.
            st.execute("GRANT SELECT ON connector." + table + " TO lumnik_app");
            st.execute("DO $$ BEGIN GRANT SELECT ON connector." + table + " TO lumnik_readonly;"
                + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
        }
    }

    private void insertMatchRow(String table, long tenant, String code, String name, String city) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connector." + table + " (tenant_id, src_code, src_name, src_city) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, tenant);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, city);
            ps.executeUpdate();
        }
    }

    private void applyMatchEntity(long tenant, String name, String table) throws Exception {
        createMatchTable(table);
        yaml(tenant).body(matchEntityYaml(name, table, "city")).post("/api/entities/apply").then().statusCode(200);
    }

    private void applySearchIndex(long tenant, String name, String entity, String... fields) {
        yaml(tenant).body(searchIndexYaml(name, entity, fields)).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));
    }

    // -- resolution: mode fixtures (CUSTOMER_ID/NAME_COL) ---------------------------------------

    private void createResolutionTable(String table) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS connector." + table);
            st.execute("CREATE TABLE connector." + table + " (tenant_id bigint NOT NULL, "
                + "\"CUSTOMER_ID\" text, \"NAME_COL\" text)");
            st.execute("ALTER TABLE connector." + table + " ENABLE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY " + table + "_iso ON connector." + table
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            st.execute("GRANT SELECT ON connector." + table + " TO lumnik_app");
            st.execute("DO $$ BEGIN GRANT SELECT ON connector." + table + " TO lumnik_readonly;"
                + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
        }
    }

    private void insertResolutionRow(String table, long tenant, String customerId, String name) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connector." + table + " (tenant_id, \"CUSTOMER_ID\", \"NAME_COL\") VALUES (?, ?, ?)")) {
            ps.setLong(1, tenant);
            ps.setString(2, customerId);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private void applyResolutionEntity(long tenant, String name, String table) throws Exception {
        createResolutionTable(table);
        String manifest = "kind: Entity\n"
            + "name: " + name + "\n"
            + "scopes: [sales]\n"
            + "sources:\n"
            + "  - table: " + table + "\n"
            + "    map:\n"
            + "      CUSTOMER_ID: customer_id\n"
            + "      NAME_COL: name\n"
            + "resolution:\n"
            + "  identity:\n"
            + "    " + table + ": customer_id\n"
            + "  cascade:\n"
            + "    - id: name\n"
            + "      exact: { fields: [name] }\n";
        yaml(tenant).body(manifest).post("/api/entities/apply").then().statusCode(200);
    }

    /** Marks one source row resolved by writing the crosswalk row directly — {@code
     *  EntityViewCompilerResolutionIT}'s own idiom (state {@code "resolved"}) — rather than driving
     *  {@code CascadeResolver}, which is out of scope for what this test needs to prove. */
    private UUID markResolved(long tenant, String entityName, String table, String customerId) throws Exception {
        UUID entityId = UUID.randomUUID();
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO core.entity_identity "
                 + "(tenant_id, entity_type, source, source_id, state, entity_id) VALUES (?, ?, ?, ?, 'resolved', ?)")) {
            ps.setLong(1, tenant);
            ps.setString(2, entityName.toLowerCase(Locale.ROOT));
            ps.setString(3, table);
            ps.setString(4, customerId);
            ps.setObject(5, entityId);
            ps.executeUpdate();
        }
        return entityId;
    }

    // -- direct reads ---------------------------------------------------------------------------

    private long markCount(long tenant, String indexName) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, indexName);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // -- 1: fresh index, existing rows -> published generation with every row -------------------

    @Test
    void freshRebuildPublishesEveryRow() throws Exception {
        long tenant = 671_601_001L;
        String entityName = unique("Rbld1");
        String table = unique("t_rbld1_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Alice", "Lyon");
        insertMatchRow(table, tenant, "c2", "Bob", "Paris");
        insertMatchRow(table, tenant, "c3", "Carl", "Paris");

        String idxName = unique("rbld1-");
        applySearchIndex(tenant, idxName, entityName, "name", "city");
        // core.search_projection_mark.id is ONE global BIGSERIAL shared by every tenant/index in this
        // suite, so the apply's own ALL mark can land at any id — never assume 0/1; capture whatever
        // it actually is and compare against THAT, the same way production code does.
        long expectedWatermark = markStore.maxMarkId(tenant, idxName);
        assertThat(expectedWatermark).as("the apply's own ALL mark exists").isPositive();

        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.rebuild(tenant, idxName);

        assertThat(sink.abandoned).as("no failure -> never abandoned").isEmpty();
        assertThat(sink.published).as("exactly one generation published").hasSize(1);
        assertThat(sink.upserted).hasSize(3);
        assertThat(sink.upserted.stream().map(d -> d.fields().get("name")).toList())
            .containsExactlyInAnyOrder("Alice", "Bob", "Carl");
        // match: mode key is the declared match columns, not the resolution {"_entity_id": ...} shape.
        assertThat(sink.upserted).allSatisfy(d -> assertThat(d.key()).containsOnlyKeys("code"));

        Optional<SearchProjectionState> state = stateStore.find(tenant, idxName);
        assertThat(state).isPresent();
        assertThat(state.get().status()).isEqualTo("ready");
        assertThat(state.get().providerName()).isEqualTo("fake");
        assertThat(state.get().liveGeneration()).isEqualTo(sink.published.get(0));
        assertThat(state.get().lastError()).isNull();
        // The apply's own ALL mark was the only mark in play; the rebuild's cleanup drains it.
        assertThat(markCount(tenant, idxName)).isZero();
        assertThat(state.get().position()).isEqualTo(expectedWatermark);
    }

    // -- 1a: a withheld index is released only by a build of what it declares now (#1219) ---------

    private boolean withheld(long tenant, String index) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT withheld FROM core.search_index WHERE tenant_id = ? AND name = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, index);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    @Test
    void aRebuildReleasesAWithheldIndexOnlyWhenItBuiltWhatTheIndexDeclaresNow() throws Exception {
        long tenant = 671_601_019L;
        String entityName = unique("Rbld19");
        String table = unique("t_rbld19_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Alice", "Lyon");
        String idxName = unique("rbld19-");
        applySearchIndex(tenant, idxName, entityName, "name", "city");
        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.rebuild(tenant, idxName);
        assertThat(withheld(tenant, idxName)).isFalse();

        // `city` is removed while a rebuild is under way: that build read the declaration with `city`,
        // so publishing it must not release the index.
        sink.onBeginGeneration = () -> applySearchIndex(tenant, idxName, entityName, "name");
        worker.rebuild(tenant, idxName);
        assertThat(sink.published).hasSize(2);
        assertThat(withheld(tenant, idxName)).as("the build that read `city` serves it").isTrue();

        // The next rebuild reads the declaration without `city`: what it serves is what is declared.
        sink.onBeginGeneration = () -> {};
        worker.rebuild(tenant, idxName);
        assertThat(sink.published).hasSize(3);
        assertThat(withheld(tenant, idxName)).isFalse();
    }

    /**
     * A re-apply locks the declaration row, then re-marks {@code ALL} — an upsert onto the mark the rebuild
     * is about to delete. The ready transaction must take the declaration row in that same order, or the
     * two wait on each other and PostgreSQL aborts one: the rebuild ends degraded, or the apply fails.
     */
    @Test
    void aReApplyLandingAsTheRebuildCommitsWaitsInsteadOfDeadlocking() throws Exception {
        long tenant = 671_601_020L;
        String entityName = unique("Rbld20");
        String table = unique("t_rbld20_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Alice", "Lyon");
        String idxName = unique("rbld20-");
        applySearchIndex(tenant, idxName, entityName, "name", "city");
        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.rebuild(tenant, idxName);
        applySearchIndex(tenant, idxName, entityName, "name"); // withheld, with its ALL mark pending

        // Stands in for a re-apply: holds the declaration row, then — once the worker is inside its ready
        // transaction — re-marks ALL and commits.
        AtomicReference<Throwable> applyFailure = new AtomicReference<>();
        Thread[] apply = new Thread[1];
        sink.onPublish = () -> {
            try {
                Connection side = adminDs.getConnection();
                side.setAutoCommit(false);
                try (PreparedStatement ps = side.prepareStatement(
                        "SELECT 1 FROM core.search_index WHERE tenant_id = ? AND name = ? FOR UPDATE")) {
                    ps.setLong(1, tenant);
                    ps.setString(2, idxName);
                    ps.executeQuery().close();
                }
                apply[0] = new Thread(() -> {
                    try (side) {
                        Thread.sleep(1_500);
                        markStore.markAll(side, tenant, idxName, entityName, "index-apply");
                        side.commit();
                    } catch (Throwable t) {
                        applyFailure.set(t);
                    }
                });
                apply[0].start();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        worker.rebuild(tenant, idxName);
        apply[0].join();

        assertThat(applyFailure.get()).as("the re-apply's mark write").isNull();
        assertThat(stateStore.find(tenant, idxName).orElseThrow().status()).isEqualTo("ready");
        assertThat(withheld(tenant, idxName)).as("the rebuild read the declaration without `city`").isFalse();
    }

    // -- 1b: a NULL returnable (non-match) column is omitted, never crashes the rebuild (C2 fix) --

    @Test
    void nullReturnableColumnIsOmittedRatherThanCrashingTheRebuild() throws Exception {
        long tenant = 671_601_002L;
        String entityName = unique("Rbld1b");
        String table = unique("t_rbld1b_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Dora", null); // "city" left NULL — routine in real ERP data
        insertMatchRow(table, tenant, "c2", "Erik", "Nancy");

        String idxName = unique("rbld1b-");
        applySearchIndex(tenant, idxName, entityName, "name", "city");

        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.rebuild(tenant, idxName); // must not throw (NPE would mean C2 regressed)

        assertThat(sink.abandoned).as("no crash -> never abandoned").isEmpty();
        assertThat(sink.published).hasSize(1);
        assertThat(sink.upserted).hasSize(2);

        ProjectionDocument dora = sink.upserted.stream()
            .filter(d -> "Dora".equals(d.fields().get("name"))).findFirst().orElseThrow();
        assertThat(dora.fields()).as("the NULL column is simply absent, never present-as-null")
            .containsEntry("name", "Dora").doesNotContainKey("city");

        ProjectionDocument erik = sink.upserted.stream()
            .filter(d -> "Erik".equals(d.fields().get("name"))).findFirst().orElseThrow();
        assertThat(erik.fields()).containsEntry("city", "Nancy");

        Optional<SearchProjectionState> state = stateStore.find(tenant, idxName);
        assertThat(state).isPresent();
        assertThat(state.get().status()).isEqualTo("ready");
    }

    // -- 2: empty mark table still rebuilds, ending with position = 0 (test 9's IT twin) --------

    @Test
    void emptyMarkTableStillRebuildsEndingAtPositionZero() throws Exception {
        long tenant = 671_602_001L;
        String entityName = unique("Rbld2");
        String table = unique("t_rbld2_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Dana", "Nice");

        String idxName = unique("rbld2-");
        applySearchIndex(tenant, idxName, entityName, "name");

        // Force the mark table empty for this pair — simulating a prior rebuild's own cleanup
        // having already drained the apply's own ALL mark, the state test 9 (unit) exercises with a
        // mock; here it must be genuinely empty in real Postgres.
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, idxName);
            ps.executeUpdate();
        }
        assertThat(markStore.maxMarkId(tenant, idxName)).isZero();

        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.rebuild(tenant, idxName);

        assertThat(sink.published).hasSize(1);
        assertThat(sink.upserted).hasSize(1);
        Optional<SearchProjectionState> state = stateStore.find(tenant, idxName);
        assertThat(state).isPresent();
        assertThat(state.get().status()).isEqualTo("ready");
        assertThat(state.get().position()).as("COALESCE(max(id), 0) on an empty table -> 0").isZero();
    }

    // -- 3: resolution: entity's unresolved rows are never projected (Review Focus item 1) ------

    @Test
    void resolutionModeExcludesUnresolvedRowsButKeepsResolvedOnes() throws Exception {
        long tenant = 671_603_001L;
        String entityName = unique("Rbld3");
        String table = unique("t_rbld3_").toLowerCase(Locale.ROOT);
        applyResolutionEntity(tenant, entityName, table);
        insertResolutionRow(table, tenant, "cust-1", "Eve");   // will be resolved
        insertResolutionRow(table, tenant, "cust-2", "Frank"); // stays unresolved

        String idxName = unique("rbld3-");
        applySearchIndex(tenant, idxName, entityName, "name");

        UUID resolvedId = markResolved(tenant, entityName, table, "cust-1");

        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.rebuild(tenant, idxName);

        assertThat(sink.abandoned).isEmpty();
        assertThat(sink.published).hasSize(1);
        assertThat(sink.upserted)
            .as("only the resolved row crosses the SPI boundary — cust-2 stays _entity_id IS NULL")
            .hasSize(1);
        ProjectionDocument doc = sink.upserted.get(0);
        assertThat(doc.id()).isEqualTo(resolvedId.toString());
        assertThat(doc.key()).containsExactly(Map.entry("_entity_id", resolvedId.toString()));
        assertThat(doc.fields().get("name")).isEqualTo("Eve");
    }

    // -- 4: seeded degraded row -> building with last_error cleared, no constraint violation ----
    // (spec test 20 — the one a mock cannot catch, since it needs a real CHECK constraint.)

    @Test
    void degradedRowRetriesIntoBuildingWithLastErrorClearedWithoutConstraintViolation() throws Exception {
        long tenant = 671_604_001L;
        String entityName = unique("Rbld4");
        String table = unique("t_rbld4_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Gina", "Metz");

        String idxName = unique("rbld4-");
        applySearchIndex(tenant, idxName, entityName, "name");

        // Seed a degraded state row from a prior failed attempt — last_error set is the shape the
        // CHECK constraint requires (status='degraded') = (last_error IS NOT NULL).
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO core.search_projection_state "
               + "(tenant_id, search_index, status, retry_count, last_error) VALUES (?, ?, 'degraded', 1, 'boom') "
               + "ON CONFLICT (tenant_id, search_index) DO UPDATE SET "
               + "status = 'degraded', retry_count = 1, last_error = 'boom'")) {
            ps.setLong(1, tenant);
            ps.setString(2, idxName);
            ps.executeUpdate();
        }

        AtomicReference<SearchProjectionState> midFlight = new AtomicReference<>();
        RecordingSink sink = new RecordingSink();
        sink.onBeginGeneration = () -> midFlight.set(stateStore.find(tenant, idxName).orElseThrow());

        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.rebuild(tenant, idxName); // must not throw (constraint violation would)

        assertThat(midFlight.get())
            .as("transitionState ran BEFORE beginGeneration, in the SAME statement that cleared "
              + "last_error — no window where status='building' AND last_error is still set")
            .isNotNull();
        assertThat(midFlight.get().status()).isEqualTo("building");
        assertThat(midFlight.get().lastError()).isNull();

        Optional<SearchProjectionState> finalState = stateStore.find(tenant, idxName);
        assertThat(finalState).isPresent();
        assertThat(finalState.get().status()).isEqualTo("ready");
        assertThat(finalState.get().retryCount()).isZero();
        assertThat(finalState.get().lastError()).isNull();
    }

    // -- 5: watermark-before-declaration-read reapply race (spec test 26), both sub-cases -------
    //
    // The pause point: SearchProjectionWorker.beforeWatermarkRead / .afterWatermarkRead, two
    // package-private Runnable test hooks (no-ops in production) added specifically for this test —
    // rebuild()'s existing code had no seam to pause at between its two admin-pool reads, and no
    // real second thread is needed here: the "concurrent" re-apply is driven synchronously from
    // inside the hook, on the SAME thread, landing deterministically at the exact instant each
    // sub-case needs (no CountDownLatch/CyclicBarrier — nothing here actually races two threads, it
    // pins the ORDER of two single-threaded events precisely, which a real thread race could only
    // approximate). This mirrors SearchIndexEntityReapplyIT.theSweepNeverInvalidatesADeclarationReplacedSinceItWasRead's
    // own "drive the two phases by hand" idiom more closely than ConnectorConcurrencyCursorIT's
    // CyclicBarrier (that test proves mutual exclusion under real contention; this one proves an
    // ordering invariant that holds regardless of contention).

    @Test
    void reapplyBeforeWatermarkReadIsSubsumedByTheRebuildsOwnCleanup() throws Exception {
        long tenant = 671_605_001L;
        String entityName = unique("Rbld5a");
        String table = unique("t_rbld5a_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Hana", "Metz");
        insertMatchRow(table, tenant, "c2", "Ivan", "Metz");

        String idxName = unique("rbld5a-");
        applySearchIndex(tenant, idxName, entityName, "name"); // v1: "name" only

        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.beforeWatermarkRead = () ->
            applySearchIndex(tenant, idxName, entityName, "name", "city"); // v2 commits before step 1

        worker.rebuild(tenant, idxName);

        assertThat(sink.published).hasSize(1);
        assertThat(sink.upserted).hasSize(2);
        assertThat(sink.upserted)
            .as("the watermark read (after the re-apply) and the declaration re-read both saw v2 "
              + "-> published from the NEW definition")
            .allSatisfy(d -> assertThat(d.fields()).containsKey("city"));
        assertThat(markCount(tenant, idxName))
            .as("the watermark captured AFTER the re-apply committed includes its own ALL mark, so "
              + "the rebuild's own at-or-below cleanup correctly subsumes it")
            .isZero();
    }

    @Test
    void reapplyAfterWatermarkReadSurvivesTheRebuildsCleanupForTheNextTick() throws Exception {
        long tenant = 671_605_002L;
        String entityName = unique("Rbld5b");
        String table = unique("t_rbld5b_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Jana", "Metz");
        insertMatchRow(table, tenant, "c2", "Karl", "Metz");

        String idxName = unique("rbld5b-");
        applySearchIndex(tenant, idxName, entityName, "name"); // v1: "name" only
        assertThat(markCount(tenant, idxName)).as("v1's own index-apply ALL mark").isEqualTo(1L);

        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.afterWatermarkRead = () ->
            applySearchIndex(tenant, idxName, entityName, "name", "city"); // v2 commits after step 1, before step 2

        worker.rebuild(tenant, idxName);

        assertThat(sink.published).hasSize(1);
        assertThat(sink.upserted).hasSize(2);
        assertThat(sink.upserted)
            .as("the declaration re-read runs AFTER the hook regardless of which sub-case this is "
              + "-> always the freshest definition, even though the watermark just below did not "
              + "see this same re-apply's mark yet")
            .allSatisfy(d -> assertThat(d.fields()).containsKey("city"));
        assertThat(markCount(tenant, idxName))
            .as("the watermark was captured BEFORE the re-apply committed, so its own ALL mark sits "
              + "ABOVE the watermark and the cleanup must leave it for the next tick, not delete it")
            .isEqualTo(1L);

        Optional<SearchProjectionState> state = stateStore.find(tenant, idxName);
        assertThat(state).isPresent();
        assertThat(state.get().status()).isEqualTo("ready");
    }

    /** Timing-sensitive by nature (spec test 26) — repeated to build confidence neither sub-case is
     *  order-dependent flakiness rather than a genuinely pinned invariant. Both hooks run
     *  synchronously on the rebuild's own thread, so there is no real scheduling nondeterminism to
     *  shake out; this repetition instead guards against a fixture/state leak between runs. */
    @RepeatedTest(3)
    void reapplyRaceSubCasesAreDeterministicAcrossRepeatedRuns() throws Exception {
        reapplyBeforeWatermarkReadIsSubsumedByTheRebuildsOwnCleanup();
        reapplyAfterWatermarkReadSurvivesTheRebuildsCleanupForTheNextTick();
    }

    // -- I3 fix: rebuild's own mark-cleanup + markReady tail commits atomically -------------------
    //
    // The happy path (freshRebuildPublishesEveryRow, etc.) cannot prove this: an implementation that
    // ran the mark-cleanup delete and the markReady update as two independently-autocommitted
    // statements would pass every other test here, and only expose its defect when the SECOND write
    // failed after the FIRST had already committed — sink.publish() has ALREADY dropped the OLD
    // generation on the provider's side by that point, so a state row left pointing at it, with the
    // ALL mark that would force a fresh rebuild already deleted, would be stuck forever. A trigger
    // poisons the markReady UPDATE for this one search index only — SearchIndexDeleteIT's own
    // poison-trigger idiom, applied to core.search_projection_state instead.

    @Test
    void aFailedMarkReadyWriteRollsBackTheMarkCleanupToo() throws Exception {
        long tenant = 671_606_001L;
        String entityName = unique("Rbld6");
        String table = unique("t_rbld6_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Liam", "Metz");

        String idxName = unique("rbld6-");
        applySearchIndex(tenant, idxName, entityName, "name");
        assertThat(markCount(tenant, idxName)).as("the apply's own single ALL mark").isEqualTo(1L);

        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE OR REPLACE FUNCTION core.search_projection_state_poison_671() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.search_index = '" + idxName + "' AND NEW.status = 'ready' THEN "
                + "RAISE EXCEPTION 'injected markReady failure'; END IF; RETURN NEW; END $$");
            st.execute("CREATE TRIGGER search_projection_state_poison_671 BEFORE UPDATE ON core.search_projection_state "
                + "FOR EACH ROW EXECUTE FUNCTION core.search_projection_state_poison_671()");
        }

        try {
            RecordingSink sink = new RecordingSink();
            SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);

            // Code review (post-#671): the poisoned write's exception is now caught around the commit
            // tail and turned into an accurate markDegraded, rather than propagating uncaught out of
            // rebuild() -- an uncaught throw here left the row stuck in 'building' with no last_error,
            // only degraded later by the reaper with a generic, factually wrong "reaped" message.
            worker.rebuild(tenant, idxName);

            assertThat(sink.published)
                .as("publish already ran before the poisoned write — this proves the exact failure "
                  + "window the fix closes, not that publish never happened")
                .hasSize(1);
            assertThat(markCount(tenant, idxName))
                .as("the mark-cleanup delete rolled back together with the failed markReady")
                .isEqualTo(1L);

            Optional<SearchProjectionState> state = stateStore.find(tenant, idxName);
            assertThat(state).isPresent();
            assertThat(state.get().status())
                .as("the commit tail's own atomic rollback never flipped it to ready; the outer catch "
                  + "then degrades it with the real cause instead of leaving it stuck in 'building'")
                .isEqualTo("degraded");
            assertThat(state.get().lastError())
                .as("the real diagnostic, not the reaper's later generic 'reaped' message — the failing step and "
                  + "its SQLSTATE, never the database's own text, which can quote values (#1299)")
                .contains("SearchProjectionWorker.commitMarkCleanupAndMarkReady failed (SQLSTATE P0001)")
                .doesNotContain("injected markReady failure");
            assertThat(state.get().liveGeneration()).as("never advanced past the failed write").isNull();
        } finally {
            try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP TRIGGER IF EXISTS search_projection_state_poison_671 ON core.search_projection_state");
                st.execute("DROP FUNCTION IF EXISTS core.search_projection_state_poison_671()");
            }
        }
    }

    /**
     * Code-review finding (post-#671): unlike the test above (a FIRST-ever build, so
     * {@code liveGeneration} was already null before the poisoned attempt), this pins the case where
     * a prior successful build already gave the row a real {@code live_generation} — {@code
     * transitionState}'s own {@code transitionToBuilding} deliberately leaves it untouched entering
     * the second attempt. Once THAT attempt's own {@code publish} succeeds and only the commit tail
     * fails, the sink has already moved past the first generation; leaving the row's memory pointed
     * at it would let a later catch-up write against a generation the sink no longer recognizes as
     * live, instead of the full rebuild a degraded row this generation-less actually needs.
     */
    @Test
    void commitTailFailureAfterAPriorSuccessfulBuildClearsTheStaleLiveGeneration() throws Exception {
        long tenant = 671_607_001L;
        String entityName = unique("Rbld7");
        String table = unique("t_rbld7_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Nadia", "Metz");

        String idxName = unique("rbld7-");
        applySearchIndex(tenant, idxName, entityName, "name");

        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.rebuild(tenant, idxName); // a first, healthy build

        Optional<SearchProjectionState> afterFirstBuild = stateStore.find(tenant, idxName);
        assertThat(afterFirstBuild).isPresent();
        assertThat(afterFirstBuild.get().status()).isEqualTo("ready");
        assertThat(afterFirstBuild.get().liveGeneration())
            .as("the first build's own real live generation").isNotNull();

        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE OR REPLACE FUNCTION core.search_projection_state_poison_671b() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.search_index = '" + idxName + "' AND NEW.status = 'ready' THEN "
                + "RAISE EXCEPTION 'injected markReady failure 2'; END IF; RETURN NEW; END $$");
            st.execute("CREATE TRIGGER search_projection_state_poison_671b BEFORE UPDATE ON core.search_projection_state "
                + "FOR EACH ROW EXECUTE FUNCTION core.search_projection_state_poison_671b()");
        }

        try {
            worker.rebuild(tenant, idxName); // the second, poisoned attempt

            Optional<SearchProjectionState> afterSecondAttempt = stateStore.find(tenant, idxName);
            assertThat(afterSecondAttempt).isPresent();
            assertThat(afterSecondAttempt.get().status()).isEqualTo("degraded");
            assertThat(afterSecondAttempt.get().liveGeneration())
                .as("the sink already moved past the first build's generation -- never leave the stale value")
                .isNull();
            assertThat(afterSecondAttempt.get().providerName()).isNull();
        } finally {
            try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP TRIGGER IF EXISTS search_projection_state_poison_671b ON core.search_projection_state");
                st.execute("DROP FUNCTION IF EXISTS core.search_projection_state_poison_671b()");
            }
        }
    }

    // -- #842: a view compiled before the column the projection reads existed ---------------------

    /** An entity applied by a hub older than the one that added {@code _canonical_key} to entity views
     *  cannot be indexed until it is applied again. The build must say that, naming the entity and the
     *  gesture, not hand the installer a SQL error about a column they never wrote. */
    @Test
    void aViewWithoutTheKeyColumnFailsTheBuildWithTheGestureThatFixesIt() throws Exception {
        long tenant = 842_001L;
        String entityName = unique("Stale842");
        String table = unique("t_stale842_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Alice", "Lyon");
        String idxName = unique("stale842-");
        applySearchIndex(tenant, idxName, entityName, "name");
        replaceViewAsAnOlderHubCompiledIt(adminDs, entityName, table, null);

        RecordingSink sink = new RecordingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);
        worker.rebuild(tenant, idxName);

        SearchProjectionState state = stateStore.find(tenant, idxName).orElseThrow();
        assertThat(state.status()).isEqualTo("degraded");
        assertThat(state.lastError())
            .contains(entityName, "_canonical_key", "apply it again", "lm entity apply")
            .doesNotContain("PSQLException", "QuarkusTransactionException");
        assertThat(sink.published).as("nothing is published from a view that cannot be read").isEmpty();
    }

    /** The named message is for the missing KEY column only. Any other undefined column stays what the
     *  database said, so a different mistake is never mislabelled as this one. */
    @Test
    void aViewMissingAnotherColumnKeepsTheDatabasesOwnError() throws Exception {
        long tenant = 842_002L;
        String entityName = unique("Stale842b");
        String table = unique("t_stale842b_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Alice", "Lyon");
        String idxName = unique("stale842b-");
        applySearchIndex(tenant, idxName, entityName, "name", "city");
        // The key column is there; the declared field `city` is not.
        replaceViewAsAnOlderHubCompiledIt(adminDs, entityName, table, "_canonical_key");

        RecordingSink sink = new RecordingSink();
        workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry).rebuild(tenant, idxName);

        SearchProjectionState state = stateStore.find(tenant, idxName).orElseThrow();
        assertThat(state.status()).isEqualTo("degraded");
        assertThat(state.lastError()).contains("city").doesNotContain("apply it again");
    }

    // -- fake sink --------------------------------------------------------------------------------

    /** Records every call a real rebuild drives a {@link ProjectionSink} through. */
    private static final class RecordingSink implements ProjectionSink {
        final List<ProjectionDocument> upserted = new CopyOnWriteArrayList<>();
        final List<String> published = new CopyOnWriteArrayList<>();
        final List<String> abandoned = new CopyOnWriteArrayList<>();
        private int generationCounter = 0;
        /** Fires at the end of {@link #beginGeneration}, once transitionState has already committed —
         *  the observation point {@code degradedRowRetriesIntoBuildingWithLastErrorClearedWithoutConstraintViolation}
         *  uses to read the state row mid-rebuild without adding any hook to production code. */
        Runnable onBeginGeneration = () -> {};
        /** Fires at the end of {@link #publish}, just before the worker's ready transaction opens. */
        Runnable onPublish = () -> {};

        @Override public String name() { return "fake"; }
        @Override public ProviderStatus status() { return null; }

        @Override public synchronized String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) {
            String gen = "gen-" + (++generationCounter);
            onBeginGeneration.run();
            return gen;
        }

        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {
            upserted.addAll(documents);
        }

        @Override public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {}

        @Override public void publish(long tenantId, String searchIndex, String generation) {
            published.add(generation);
            onPublish.run();
        }

        @Override public void abandon(long tenantId, String searchIndex, String generation) {
            abandoned.add(generation);
        }

        @Override public void purgeIndex(long tenantId, String searchIndex) {}

        @Override public long liveDocumentCount(long tenantId, String searchIndex) { return 0L; }
    }
}
