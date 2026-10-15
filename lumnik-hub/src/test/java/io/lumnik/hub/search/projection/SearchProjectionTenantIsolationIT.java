package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.matchEntityYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.workerWith;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Spec test 15, run under {@link RlsEnforcedProfile} — CLAUDE.md's own rule that RLS is only
 * genuinely tested through this harness (the plain {@code @QuarkusTest} DevServices container
 * connects as an RLS-bypassing owner, so a missing tenant scope would be invisible there).
 *
 * <p>Both {@code SearchProjectionFullRebuildIT} and {@code SearchProjectionIncrementalCatchupIT}
 * give every tenant its OWN physical source table — enough to prove the projection logic itself,
 * but not enough to catch a forgotten tenant filter: two disjoint tables can never collide by
 * accident. This class deliberately does the opposite: ONE {@code match:} entity, ONE physical
 * table, carrying rows for TWO tenants that share the identical raw match-column value — so their
 * {@code _canonical_key} strings (derived purely from that raw value, see {@code
 * EntityViewCompiler}) are byte-for-byte equal. Each tenant declares its OWN {@code SearchIndex}
 * over that one shared entity. If the rebuild scan or catch-up's single-row read ever forgot to
 * scope by tenant, Postgres would happily return either row — or both — for a query keyed only on
 * that shared canonical key; real RLS (the non-owner {@code lumnik_app}/{@code lumnik_readonly}
 * roles this profile connects as) is what actually prevents that.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class SearchProjectionTenantIsolationIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;

    // -- match: mode fixtures, one shared table for both tenants -------------------------------

    private void createMatchTable(String table) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS connector." + table);
            st.execute("CREATE TABLE connector." + table + " (tenant_id bigint NOT NULL, "
                + "src_code text, src_name text, src_city text)");
            st.execute("ALTER TABLE connector." + table + " ENABLE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY " + table + "_iso ON connector." + table
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            // Under RlsEnforcedProfile BOTH the "default" (rebuild via /api/entities/apply and
            // /api/search-indexes/apply run under lumnik_app) and "readonly" (the rebuild scan and
            // catch-up's single-row read) datasources connect as genuine non-owner roles — the
            // whole reason this test exists — so both grants are load-bearing here, unlike the
            // guarded "best effort" grant SearchProjectionFullRebuildIT uses for its own plain
            // @QuarkusTest (no RlsEnforcedProfile there, so lumnik_readonly may not even exist).
            st.execute("GRANT SELECT ON connector." + table + " TO lumnik_app");
            st.execute("GRANT SELECT ON connector." + table + " TO lumnik_readonly");
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

    private void updateMatchRowCity(String table, long tenant, String code, String newCity) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE connector." + table + " SET src_city = ? WHERE tenant_id = ? AND src_code = ?")) {
            ps.setString(1, newCity);
            ps.setLong(2, tenant);
            ps.setString(3, code);
            ps.executeUpdate();
        }
    }

    /** Entity manifests are deployment-global (no tenant column) — applied once, under whichever
     *  tenant's identity, and visible to every tenant's own SearchIndex declaration afterward. */
    private void applySharedMatchEntity(long applyingTenant, String name, String table) throws Exception {
        createMatchTable(table);
        yaml(applyingTenant).body(matchEntityYaml(name, table, "city")).post("/api/entities/apply").then().statusCode(200);
    }

    private void applySearchIndex(long tenant, String name, String entity, String... fields) {
        yaml(tenant).body(searchIndexYaml(name, entity, fields)).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));
    }

    // -- 1: identical raw match value in two tenants' rows of the SAME table never merges,
    //       and a rebuild for one tenant never touches the other's row -----------------------

    @Test
    void identicalMatchValuesInTwoTenantsNeverMergeDuringRebuild() throws Exception {
        long tenantA = 671_900_001L;
        long tenantB = 671_900_002L;
        String entityName = unique("TenIso1");
        String table = unique("t_teniso1_").toLowerCase(Locale.ROOT);
        applySharedMatchEntity(tenantA, entityName, table);

        String sharedCode = "SHARED-CODE-1"; // identical raw match value for both tenants' rows
        insertMatchRow(table, tenantA, sharedCode, "Alice", "Lyon");
        insertMatchRow(table, tenantB, sharedCode, "Bob", "Berlin");

        String idxA = unique("teniso1a-");
        String idxB = unique("teniso1b-");
        applySearchIndex(tenantA, idxA, entityName, "name", "city");
        applySearchIndex(tenantB, idxB, entityName, "name", "city");

        TrackingSink sink = new TrackingSink();
        SearchProjectionWorker worker = workerWith(sink, markStore, stateStore, adminDs, readonlyDs, registry);

        worker.rebuild(tenantA, idxA);
        int afterA = sink.calls.size();
        assertThat(sink.calls.subList(0, afterA))
            .as("tenant B's id never appears while tenant A's rebuild runs")
            .allSatisfy(c -> assertThat(c.tenantId()).isEqualTo(tenantA));
        List<TrackingSink.Call> upsertsA = sink.upsertsSince(0);
        assertThat(upsertsA).hasSize(1);
        assertThat(upsertsA.get(0).doc().fields().get("name")).isEqualTo("Alice");
        assertThat(upsertsA.get(0).doc().fields().get("city")).isEqualTo("Lyon");

        worker.rebuild(tenantB, idxB);
        assertThat(sink.calls.subList(afterA, sink.calls.size()))
            .as("tenant A's id never appears while tenant B's rebuild runs")
            .allSatisfy(c -> assertThat(c.tenantId()).isEqualTo(tenantB));
        List<TrackingSink.Call> upsertsB = sink.upsertsSince(afterA);
        assertThat(upsertsB).hasSize(1);
        assertThat(upsertsB.get(0).doc().fields().get("name")).isEqualTo("Bob");
        assertThat(upsertsB.get(0).doc().fields().get("city")).isEqualTo("Berlin");

        // The raw match value is identical, so the canonical key STRING is identical too — proving
        // the two documents never merged is about the tenant-scoped call boundary above, not a
        // different id. A real merge would instead show up as one tenant's upsert carrying the
        // OTHER tenant's field values, which the assertions above already rule out.
        assertThat(upsertsA.get(0).doc().id()).isEqualTo(upsertsB.get(0).doc().id());
    }

    // -- 2: catch-up's single-row re-read for one tenant never resolves to the other tenant's
    //       row, even though both rows share the same canonical key in the same view -----------

    @Test
    void catchUpForOneTenantNeverTouchesTheOtherTenantsRow() throws Exception {
        long tenantA = 671_900_101L;
        long tenantB = 671_900_102L;
        String entityName = unique("TenIso2");
        String table = unique("t_teniso2_").toLowerCase(Locale.ROOT);
        applySharedMatchEntity(tenantA, entityName, table);

        String sharedCode = "SHARED-CODE-2";
        insertMatchRow(table, tenantA, sharedCode, "Carl", "Metz");
        insertMatchRow(table, tenantB, sharedCode, "Dana", "Nancy");

        String idxA = unique("teniso2a-");
        String idxB = unique("teniso2b-");
        applySearchIndex(tenantA, idxA, entityName, "name", "city");
        applySearchIndex(tenantB, idxB, entityName, "name", "city");

        // Bring both pairs to "ready" first — catch-up requires an existing state row.
        TrackingSink rebuildSink = new TrackingSink();
        SearchProjectionWorker rebuildWorker = workerWith(rebuildSink, markStore, stateStore, adminDs, readonlyDs, registry);
        rebuildWorker.rebuild(tenantA, idxA);
        rebuildWorker.rebuild(tenantB, idxB);

        String keyA = rebuildSink.upsertsSince(0).stream()
            .filter(c -> c.tenantId() == tenantA).findFirst().orElseThrow().doc().id();
        long positionBBefore = stateStore.find(tenantB, idxB).orElseThrow().position();

        // Mutate and re-mark ONLY tenant A's row — tenant B's mark table/state stay untouched.
        updateMatchRowCity(table, tenantA, sharedCode, "Marseille");
        markStore.markKeys(tenantA, idxA, entityName, List.of(keyA), "tenant-iso-test");

        TrackingSink catchupSink = new TrackingSink();
        SearchProjectionWorker catchupWorker = workerWith(catchupSink, markStore, stateStore, adminDs, readonlyDs, registry);
        CatchUpResult result = catchupWorker.catchUp(tenantA, idxA);

        assertThat(result).isNotEqualTo(CatchUpResult.REBUILD);
        assertThat(catchupSink.calls)
            .as("catch-up for tenant A never records a call tagged with tenant B's id")
            .allSatisfy(c -> assertThat(c.tenantId()).isEqualTo(tenantA));
        List<TrackingSink.Call> upserts = catchupSink.upsertsSince(0);
        assertThat(upserts).hasSize(1);
        assertThat(upserts.get(0).doc().fields().get("name"))
            .as("the single-row re-read, scoped to tenant A, must resolve to A's OWN row — never "
              + "B's, despite both sharing the identical canonical key in the same view")
            .isEqualTo("Carl");
        assertThat(upserts.get(0).doc().fields().get("city")).isEqualTo("Marseille");

        // Tenant B's own state is untouched by tenant A's catch-up.
        assertThat(stateStore.find(tenantB, idxB).orElseThrow().position()).isEqualTo(positionBBefore);
    }

    // -- fake sink ----------------------------------------------------------------------------

    /** Records every call a real rebuild/catch-up drives a {@link ProjectionSink} through, tagged
     *  with the {@code tenantId} each call carries — the isolation oracle this whole class checks. */
    private static final class TrackingSink implements ProjectionSink {
        record Call(long tenantId, String method, ProjectionDocument doc) {}

        final List<Call> calls = new CopyOnWriteArrayList<>();
        private int generationCounter = 0;

        List<Call> upsertsSince(int index) {
            return calls.subList(index, calls.size()).stream()
                .filter(c -> "upsert".equals(c.method())).toList();
        }

        @Override public String name() { return "fake"; }
        @Override public ProviderStatus status() { return null; }

        @Override public synchronized String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) {
            calls.add(new Call(tenantId, "beginGeneration", null));
            return "gen-" + (++generationCounter);
        }

        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {
            for (ProjectionDocument d : documents) calls.add(new Call(tenantId, "upsert", d));
        }

        @Override public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {
            for (String id : ids) calls.add(new Call(tenantId, "delete", new ProjectionDocument(id, Map.of(), Map.of())));
        }

        @Override public void publish(long tenantId, String searchIndex, String generation) {
            calls.add(new Call(tenantId, "publish", null));
        }

        @Override public void abandon(long tenantId, String searchIndex, String generation) {
            calls.add(new Call(tenantId, "abandon", null));
        }

        @Override public void purgeIndex(long tenantId, String searchIndex) {
            calls.add(new Call(tenantId, "purgeIndex", null));
        }

        @Override public long liveDocumentCount(long tenantId, String searchIndex) { return 0L; }
    }
}
