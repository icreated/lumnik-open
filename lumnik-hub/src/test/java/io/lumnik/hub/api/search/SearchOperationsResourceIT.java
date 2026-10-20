package io.lumnik.hub.api.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.run.RunLock;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.lumnik.hub.search.SearchCapability;
import io.lumnik.hub.search.SearchGate;
import io.lumnik.hub.search.projection.SearchProjectionLockKey;
import io.lumnik.hub.search.projection.SearchProjectionState;
import io.lumnik.hub.search.projection.SearchProjectionStateStore;
import io.lumnik.hub.search.projection.SearchProjectionWorker;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.SearchAvailability;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static io.lumnik.hub.api.search.SearchOperationsItSupport.auditRows;
import static io.lumnik.hub.api.search.SearchOperationsItSupport.entity;
import static io.lumnik.hub.api.search.SearchOperationsItSupport.freeEntities;
import static io.lumnik.hub.api.search.SearchOperationsItSupport.searchIndex;
import static io.lumnik.hub.api.search.SearchOperationsItSupport.unique;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #703's operations door under dev-bypass: 404s, 409s, 501, 202s, the first-build-no-row shape and
 * tenant isolation. Scope refusal (403) lives in {@link SearchOperationsScopeIT}, which needs real
 * bearer tokens — {@code SearchIndexApplyResourceIT}/{@code SearchIndexScopeIT}'s own split, mirrored
 * exactly.
 */
@QuarkusTest
class SearchOperationsResourceIT {

    private static final String ENTITY = "SearchOps703";
    private static final String TABLE = "t_searchops703_erp";
    /** A second tenant for the one test that needs two indexes in different states (one per Entity, #832). */
    private static final long READY_TENANT = 970_907L;

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject SearchGate realSearchGate;
    @Inject SearchProjectionStateStore stateStore;
    @Inject SearchProjectionWorker realWorker;
    @Inject ProjectionSinkGate realSinkGate;

    private static RequestSpecification yaml(long tenant) {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/yaml");
    }

    private static RequestSpecification as(long tenant) {
        return given().headers(TestIdentity.tenantAndUser(tenant));
    }

    @BeforeEach
    void applyEntity() throws Exception {
        freeEntities(adminDs, ENTITY);
        // Deployment-global, applied once regardless of which tenant later declares a SearchIndex
        // over it.
        yaml(1).body(entity(adminDs, ENTITY, TABLE)).post("/api/entities/apply").then().statusCode(200);
    }

    /** Always undoes any {@code SearchGate}/{@code SearchProjectionWorker}/{@code
     *  SearchProjectionStateStore}/{@code ProjectionSinkGate} mock this class installed, whether or
     *  not the test that ran actually installed one — restoring the real, CDI-managed singleton is
     *  always safe. {@code stateStore}/{@code realWorker}/{@code realSinkGate} are captured at
     *  injection time, before any test's own mock install ever runs, so they are always the genuine
     *  bean. */
    @AfterEach
    void restoreRealCollaborators() {
        QuarkusMock.installMockForType(realSearchGate, SearchGate.class);
        QuarkusMock.installMockForType(realWorker, SearchProjectionWorker.class);
        QuarkusMock.installMockForType(stateStore, SearchProjectionStateStore.class);
        QuarkusMock.installMockForType(realSinkGate, ProjectionSinkGate.class);
    }

    private String applyIndex(long tenant, String name) {
        yaml(tenant).body(searchIndex(name, ENTITY)).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));
        return name;
    }

    private void mockSearchGateAvailable() {
        SearchGate mockGate = mock(SearchGate.class);
        when(mockGate.capability()).thenReturn(new SearchCapability(SearchAvailability.AVAILABLE, "fake", null, "1"));
        QuarkusMock.installMockForType(mockGate, SearchGate.class);
    }

    // -- 1: unknown name -------------------------------------------------------------------------

    @Test
    void unknownNameIs404OnEveryRoute() {
        String name = unique("missing");
        as(1).get("/api/platform/search/index/" + name + "/status").then().statusCode(404);
        as(1).post("/api/platform/search/index/" + name + "/rebuild").then().statusCode(404);
        as(1).post("/api/platform/search/index/" + name + "/cancel").then().statusCode(404);
    }

    // -- 3: fresh valid index, never built --------------------------------------------------------

    @Test
    void listAndStatusShowBuildingWithNoDocumentCountBeforeTheFirstBuild() {
        long tenant = 970_001L;
        String name = applyIndex(tenant, unique("fresh"));

        as(tenant).get("/api/platform/search/index")
            .then().statusCode(200)
            .body("find { it.name == '" + name + "' }.status", equalTo("building"))
            .body("find { it.name == '" + name + "' }.documentCount", nullValue())
            .body("find { it.name == '" + name + "' }.documentCountStale", equalTo(true))
            .body("find { it.name == '" + name + "' }.documentCountAsOf", nullValue());

        as(tenant).get("/api/platform/search/index/" + name + "/status")
            .then().statusCode(200)
            .body("status", equalTo("building"))
            .body("documentCount", nullValue())
            .body("documentCountStale", equalTo(true))
            .body("documentCountAsOf", nullValue())
            .body("lastError", nullValue())
            .body("activeRun", nullValue())
            .body("invalid", equalTo(false))
            .body("withheld", equalTo(false));
    }

    // -- 3b: a withheld index says so on both doors (#1219) ----------------------------------------

    @Test
    void listAndStatusSayAnIndexIsWithheldUntilRebuilt() throws Exception {
        long tenant = 970_019L;
        String name = applyIndex(tenant, unique("withheld"));
        assertThat(Jdbc.update(adminDs, "withhold",
            "UPDATE core.search_index SET withheld = true WHERE tenant_id = ? AND name = ?",
            ps -> { ps.setLong(1, tenant); ps.setString(2, name); }))
            .isEqualTo(1);

        as(tenant).get("/api/platform/search/index")
            .then().statusCode(200)
            .body("find { it.name == '" + name + "' }.withheld", equalTo(true));
        as(tenant).get("/api/platform/search/index/" + name + "/status")
            .then().statusCode(200)
            .body("withheld", equalTo(true))
            .body("invalid", equalTo(false));
    }

    /** Code review finding: {@code updateDocumentCount}'s return doubles as the proof that the
     *  generation/phase didn't change out from under this GET's own live call — a write failure
     *  means that proof could not run, and must be treated the same as "the cutover happened"
     *  rather than trusting the live value anyway. Forces the write to throw directly (same
     *  {@code QuarkusMock} idiom as {@link #cancelAnswers503WhenTheStateStoreThrowsATechnicalFailure})
     *  rather than trying to land the real timing race. */
    @Test
    void statusFallsBackToTheCacheWhenTheDocumentCountWriteThrows() {
        long tenant = 970_012L;
        String name = applyIndex(tenant, unique("doc-count-write-fails"));

        ProjectionSink fakeSink = mock(ProjectionSink.class);
        when(fakeSink.liveDocumentCount(tenant, name)).thenReturn(42L);
        ProjectionSinkGate mockSinkGate = mock(ProjectionSinkGate.class);
        when(mockSinkGate.resolve()).thenReturn(Optional.of(fakeSink));
        QuarkusMock.installMockForType(mockSinkGate, ProjectionSinkGate.class);

        SearchProjectionStateStore throwingStateStore = mock(SearchProjectionStateStore.class);
        when(throwingStateStore.find(tenant, name)).thenReturn(Optional.of(
            new SearchProjectionState(tenant, name, "postgres", "gen-1", 0L, "ready", 0, null)));
        when(throwingStateStore.updateDocumentCount(eq(tenant), eq(name), eq(42L), eq("gen-1")))
            .thenThrow(new RuntimeException("connection failure"));
        QuarkusMock.installMockForType(throwingStateStore, SearchProjectionStateStore.class);

        as(tenant).get("/api/platform/search/index/" + name + "/status")
            .then().statusCode(200)
            .body("liveGeneration", equalTo("gen-1"))
            .body("documentCount", nullValue())
            .body("documentCountStale", equalTo(true));
    }

    // -- 4: tenant isolation on the admin-pool read (Review Focus, brief's own verbatim scenario) --

    @Test
    void statusForANameThatExistsOnlyForADifferentTenantIs404NeverThatTenantsRow() {
        long ownerTenant = 950L;
        long callerTenant = 951L;
        String name = applyIndex(ownerTenant, unique("idx"));

        as(callerTenant)
            .get("/api/platform/search/index/" + name + "/status")
            .then().statusCode(404);
    }

    // -- 5: rebuild refuses an invalid index --------------------------------------------------------

    @Test
    void rebuildRefusesAnInvalidIndexWith409() throws Exception {
        long tenant = 970_002L;
        String name = applyIndex(tenant, unique("invalid"));
        assertThat(Jdbc.update(adminDs, "mark invalid",
            "UPDATE core.search_index SET status = 'invalid', invalid_reason = ? WHERE tenant_id = ? AND name = ?",
            ps -> { ps.setString(1, "entity column dropped"); ps.setLong(2, tenant); ps.setString(3, name); }))
            .isEqualTo(1);

        as(tenant).post("/api/platform/search/index/" + name + "/rebuild")
            .then().statusCode(409)
            .body("error", equalTo("index invalid"))
            .body("invalidReason", equalTo("entity column dropped"));
    }

    // -- 6: rebuild without a provider is 501 -------------------------------------------------------

    @Test
    // PR #734 review finding: asserts the no-provider posture itself, which cannot hold under
    // -Psearch — see PlatformHealthResourceSearchIT's identical note.
    @DisabledIfSystemProperty(named = "lumnik.test.searchContractSuiteActive", matches = "true")
    void rebuildAnswers501WhenNoSearchProviderIsInstalled() {
        long tenant = 970_003L;
        String name = applyIndex(tenant, unique("noprovider"));

        as(tenant).post("/api/platform/search/index/" + name + "/rebuild")
            .then().statusCode(501)
            .body("error", equalTo("search not installed"));
    }

    // -- 7: rebuild against a resolvable provider starts a real run --------------------------------

    @Test
    void rebuildAgainstAResolvableProviderStartsAndReturnsARunId() {
        long tenant = 970_004L;
        String name = applyIndex(tenant, unique("rebuild-ok"));
        mockSearchGateAvailable();

        String runId = as(tenant).post("/api/platform/search/index/" + name + "/rebuild")
            .then().statusCode(202)
            .extract().path("runId");
        assertThat(UUID.fromString(runId)).isNotNull(); // parses -> genuinely a UUID, not a placeholder
        assertThat(auditRows(adminDs, name, "REBUILD")).isEqualTo(1); // spec §4: audited on acceptance
    }

    // -- 7b: a second rebuild while another run already holds the pair's lock is 409 ----------------

    /**
     * {@code SearchProjectionTriggerRebuildIT.returnsRebuildAlreadyRunningWhenTheSamePairHasAnActiveRun}'s
     * own idiom: hold the pair's advisory lock on a side connection to stand in for "another worker's
     * run is genuinely still in flight" — deterministic, no racing against the real (provider-less)
     * background rebuild's own near-instant failure.
     */
    @Test
    void aSecondRebuildWhileAnotherRunHoldsTheLockIs409WithTheSameRunId() throws Exception {
        long tenant = 970_005L;
        String name = applyIndex(tenant, unique("rebuild-busy"));
        UUID runningRunId = UUID.randomUUID();
        stateStore.insertBuilding(tenant, name, runningRunId, Instant.now());
        mockSearchGateAvailable();

        UUID lockKey = SearchProjectionLockKey.of(tenant, name);
        try (RunLock heldByAnotherWorker = RunLock.tryAcquire(adminDs, lockKey)) {
            assertThat(heldByAnotherWorker).isNotNull();

            as(tenant).post("/api/platform/search/index/" + name + "/rebuild")
                .then().statusCode(409)
                .body("error", equalTo("rebuild already in progress"))
                .body("runId", equalTo(runningRunId.toString()));
        }
    }

    // -- 7c: a genuine technical failure from the worker is a 503, never a bare 500 -----------------

    /** Code review finding: {@code SearchProjectionWorker.triggerRebuild} throws for a genuine
     *  technical failure (a {@code RunLock.tryAcquire} SQLException, a rejected executor submit) —
     *  the REST layer must map that to 503, never let it surface as a bare 500. Forced here with a
     *  {@code QuarkusMock}-installed fake worker, {@code SearchOperationsResourceIT}'s own established
     *  idiom for standing in for a collaborator's failure mode. */
    @Test
    void rebuildAnswers503WhenTheWorkerThrowsATechnicalFailure() {
        long tenant = 970_010L;
        String name = applyIndex(tenant, unique("rebuild-503"));
        mockSearchGateAvailable();

        SearchProjectionWorker throwingWorker = mock(SearchProjectionWorker.class);
        when(throwingWorker.triggerRebuild(anyLong(), anyString(), any()))
            .thenThrow(new IllegalStateException("could not attempt the lock"));
        QuarkusMock.installMockForType(throwingWorker, SearchProjectionWorker.class);

        as(tenant).post("/api/platform/search/index/" + name + "/rebuild")
            .then().statusCode(503)
            .body("error", equalTo("search rebuild unavailable"));
    }

    // -- 8: cancel ----------------------------------------------------------------------------------

    @Test
    // PR #734 review finding: the setup assertion below asserts the no-provider posture itself
    // (proving cancel is provider-independent by way of an environment where none exists), which
    // cannot hold under -Psearch — see PlatformHealthResourceSearchIT's identical note.
    @DisabledIfSystemProperty(named = "lumnik.test.searchContractSuiteActive", matches = "true")
    void cancelIsIdempotentWhileTheRunIsActiveAndNeverConsultsSearchGate() {
        long tenant = 970_006L;
        String name = applyIndex(tenant, unique("cancel-active"));
        UUID runId = UUID.randomUUID();
        stateStore.insertBuilding(tenant, name, runId, Instant.now());

        // Scenario 9's own point, made explicit here rather than in a separate test: cancel is
        // provider-independent, so it must succeed even though this environment installs none.
        as(tenant).get("/api/search/capability").then().statusCode(200).body("state", equalTo("unavailable"));

        as(tenant).post("/api/platform/search/index/" + name + "/cancel")
            .then().statusCode(202)
            .body("runId", equalTo(runId.toString()));

        // Re-cancelling a still-active (not yet reaped/completed) run answers the SAME 202 again.
        as(tenant).post("/api/platform/search/index/" + name + "/cancel")
            .then().statusCode(202)
            .body("runId", equalTo(runId.toString()));

        assertThat(auditRows(adminDs, name, "CANCEL")).isEqualTo(2); // spec §4: audited on acceptance, each time
    }

    @Test
    void cancelRefusesWhenNoRebuildIsInProgress() {
        long tenant = 970_007L;

        // Never built at all: no core.search_projection_state row exists yet.
        String neverBuilt = applyIndex(tenant, unique("cancel-never-built"));
        as(tenant).post("/api/platform/search/index/" + neverBuilt + "/cancel")
            .then().statusCode(409)
            .body("error", equalTo("no rebuild in progress"));

        // Ready (a prior build finished, nothing active now). Another tenant: one SearchIndex per Entity (#832).
        String ready = applyIndex(READY_TENANT, unique("cancel-ready"));
        assertThat(Jdbc.update(adminDs, "insert ready row",
            "INSERT INTO core.search_projection_state (tenant_id, search_index, status) VALUES (?, ?, 'ready')",
            ps -> { ps.setLong(1, READY_TENANT); ps.setString(2, ready); })).isEqualTo(1);
        as(READY_TENANT).post("/api/platform/search/index/" + ready + "/cancel")
            .then().statusCode(409)
            .body("error", equalTo("no rebuild in progress"));
    }

    @Test
    void cancelRefusesOncePastThePublishingCheckpoint() throws Exception {
        long tenant = 970_008L;
        String name = applyIndex(tenant, unique("cancel-publishing"));
        UUID runId = UUID.randomUUID();
        stateStore.insertBuilding(tenant, name, runId, Instant.now());
        assertThat(Jdbc.update(adminDs, "flip to publishing",
            "UPDATE core.search_projection_state SET active_run_phase = 'publishing' "
          + "WHERE tenant_id = ? AND search_index = ?",
            ps -> { ps.setLong(1, tenant); ps.setString(2, name); })).isEqualTo(1);

        as(tenant).post("/api/platform/search/index/" + name + "/cancel")
            .then().statusCode(409)
            .body("error", equalTo("rebuild already past its cancellable point"))
            .body("runId", equalTo(runId.toString()));
    }

    /** Code review finding: {@code SearchProjectionStateStore.requestCancel} throws {@code
     *  IllegalStateException} for a genuine connection/transaction failure — same posture as
     *  rebuild's own 503 mapping, forced here the same way. */
    @Test
    void cancelAnswers503WhenTheStateStoreThrowsATechnicalFailure() {
        long tenant = 970_011L;
        String name = applyIndex(tenant, unique("cancel-503"));

        SearchProjectionStateStore throwingStateStore = mock(SearchProjectionStateStore.class);
        when(throwingStateStore.requestCancel(anyLong(), anyString()))
            .thenThrow(new IllegalStateException("connection failure"));
        QuarkusMock.installMockForType(throwingStateStore, SearchProjectionStateStore.class);

        as(tenant).post("/api/platform/search/index/" + name + "/cancel")
            .then().statusCode(503)
            .body("error", equalTo("search cancel unavailable"));
    }
}
