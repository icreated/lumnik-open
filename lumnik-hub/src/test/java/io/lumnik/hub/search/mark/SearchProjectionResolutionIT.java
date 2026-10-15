package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.resolution.CascadeResolver;
import io.lumnik.hub.entity.resolution.EntityResolutionState;
import io.lumnik.hub.entity.resolution.ResolutionResult;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.applyResolutionEntity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #701 phase 5, trigger row 3 (design doc §1's correction, §2, §4): the resolution-driven {@code
 * KEY} mark producer inside {@link CascadeResolver#resolveWithin}. Calls {@code CascadeResolver}
 * directly with hand-built cascade rules, the same idiom {@code CascadeResolverTest} already uses,
 * rather than driving a full ingestion run through {@code EntityResolutionPass} — {@code resolve()}
 * is {@code resolveWithin}'s one real-world entry point and the one this hook lives inside, so
 * calling it directly exercises exactly the seam this phase adds.
 *
 * <p>The entity manifest is still applied via {@link SearchProjectionMarkItSupport#applyResolutionEntity}
 * (mirroring {@code SearchProjectionIngestionIT}'s {@code resolutionModeEntityProducesNothingFromThisHook})
 * purely so {@code POST /api/search-indexes/apply} has a real, stored entity to validate the SearchIndex
 * declaration against — its own {@code resolution:}/{@code cascade:} block is never actually
 * exercised by this test, since {@link CascadeResolver#resolve} is called with its own, separately
 * constructed {@link EntityManifest.CascadeRule} list.
 */
@QuarkusTest
class SearchProjectionResolutionIT {

    @Inject CascadeResolver resolver;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static EntityManifest.CascadeRule nameRule() {
        return new EntityManifest.CascadeRule(
            "name", new EntityManifest.Exact(List.of("name"), List.of("clean", "lower")), null);
    }

    private long markCount(long tenant, String indexName, String canonicalKey) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ? "
               + "AND mark_kind = 'KEY' AND reason = 'resolution-decision' AND canonical_key = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, indexName);
            ps.setString(3, canonicalKey);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Scoped to THIS producer's own marks — {@code mark_kind = 'KEY' AND reason =
     * 'resolution-decision'}, exactly {@link #markCount}'s own filter minus the {@code
     * canonical_key} constraint — not every row in the tenant. Applying the SearchIndex itself
     * triggers row 4a (phase 2: {@code SearchIndexStore.upsert} on {@code APPLIED} writes its own
     * {@code ALL}/{@code index-apply} mark), so an unfiltered {@code count(*)} would always be off
     * by one and make every "nothing was marked yet" assertion in this file false by construction.
     */
    private long totalMarkCount(long tenant) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark "
               + "WHERE tenant_id = ? AND mark_kind = 'KEY' AND reason = 'resolution-decision'")) {
            ps.setLong(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Simulates #671 having already drained (deleted) a mark — the idiom the design doc's own
     *  idempotency reasoning names: "once #671 consumes ... a row, a later identical trigger is
     *  free to re-insert". Used to prove a later {@code resolveWithin} call genuinely re-marks a
     *  key, rather than the assertion merely tolerating a row that was already there. */
    private void deleteMark(long tenant, String indexName, String canonicalKey) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM core.search_projection_mark WHERE tenant_id = ? AND search_index = ? "
               + "AND mark_kind = 'KEY' AND reason = 'resolution-decision' AND canonical_key = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, indexName);
            ps.setString(3, canonicalKey);
            ps.executeUpdate();
        }
    }

    private static void applySearchIndex(long tenant, String indexName, String entityName) {
        yaml(tenant).body(searchIndex(indexName, entityName, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);
    }

    // -- Design doc §7 test 4 -------------------------------------------------------------------

    @Test
    void freshMintProducesOneKeyMarkAndNoPriorMark() throws Exception {
        long tenant = 701_501;
        String entityName = "SearchRes701A" + System.nanoTime();
        String table = "t_search_res_701a_" + System.nanoTime();
        applyResolutionEntity(adminDs, tenant, entityName, table);

        String indexName = unique("mark-res-a");
        applySearchIndex(tenant, indexName, entityName);

        String entityType = entityName.toLowerCase(Locale.ROOT);
        ResolutionResult result = resolver.resolve(tenant, entityType, "erp", "cust-1",
            List.of(nameRule()), Map.of("name", "Alice"), null);

        assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
        assertThat(result.newlyMinted()).isTrue();
        assertThat(result.entityId()).isNotNull();

        assertThat(markCount(tenant, indexName, result.entityId().toString()))
            .as("the newly-minted entity_id gets exactly one KEY mark")
            .isEqualTo(1L);
        // No prior link existed, so nothing else was marked.
        assertThat(totalMarkCount(tenant)).isEqualTo(1L);
    }

    @Test
    void mergeViaAssertionRealizedAtNextResolveMarksBothTheSurvivorAndThePriorEntity() throws Exception {
        long tenant = 701_502;
        String entityName = "SearchRes701B" + System.nanoTime();
        String table = "t_search_res_701b_" + System.nanoTime();
        applyResolutionEntity(adminDs, tenant, entityName, table);

        String indexName = unique("mark-res-b");
        applySearchIndex(tenant, indexName, entityName);

        String entityType = entityName.toLowerCase(Locale.ROOT);

        ResolutionResult resultA = resolver.resolve(tenant, entityType, "erp", "cust-a",
            List.of(nameRule()), Map.of("name", "Alice"), null);
        ResolutionResult resultB = resolver.resolve(tenant, entityType, "crm", "cust-b",
            List.of(nameRule()), Map.of("name", "Bob"), null);
        UUID entityA = resultA.entityId();
        UUID entityB = resultB.entityId();
        assertThat(entityA).isNotEqualTo(entityB);

        // Both fresh mints already marked their own entity_id -- delete both, simulating #671
        // having already drained them, so the merge-realizing resolve() below is the only thing
        // that could put either row back.
        deleteMark(tenant, indexName, entityA.toString());
        deleteMark(tenant, indexName, entityB.toString());
        assertThat(totalMarkCount(tenant)).isZero();

        UUID refA = identityRefOf(tenant, "erp", "cust-a");
        UUID refB = identityRefOf(tenant, "crm", "cust-b");

        resolver.assertSame(tenant, entityType, refA, refB, "reviewer-1", "same customer");
        // The corrected design doc: an assertion alone writes only a crosswalk fact, no mark.
        assertThat(totalMarkCount(tenant))
            .as("assertSame alone must not mark anything -- entity_id has not changed yet")
            .isZero();

        // Realize the assertion: a later resolve() call for one of the two affected rows now
        // outranks the cascade (step 0) and resolves cust-b to entityA instead of entityB.
        ResolutionResult merged = resolver.resolve(tenant, entityType, "crm", "cust-b",
            List.of(nameRule()), Map.of("name", "Bob"), null);

        assertThat(merged.state()).isEqualTo(EntityResolutionState.RESOLVED);
        assertThat(merged.entityId()).isEqualTo(entityA);

        assertThat(markCount(tenant, indexName, entityA.toString()))
            .as("the surviving entity_id is marked").isEqualTo(1L);
        assertThat(markCount(tenant, indexName, entityB.toString()))
            .as("the entity_id this row moved away from is marked too, so #671 can retract its "
              + "stale contribution").isEqualTo(1L);
        assertThat(totalMarkCount(tenant)).isEqualTo(2L);
    }

    @Test
    void unchangedResolveCallCollapsesToOneMarkRowUnderTheUniqueConstraint() throws Exception {
        long tenant = 701_503;
        String entityName = "SearchRes701C" + System.nanoTime();
        String table = "t_search_res_701c_" + System.nanoTime();
        applyResolutionEntity(adminDs, tenant, entityName, table);

        String indexName = unique("mark-res-c");
        applySearchIndex(tenant, indexName, entityName);

        String entityType = entityName.toLowerCase(Locale.ROOT);

        ResolutionResult first = resolver.resolve(tenant, entityType, "erp", "cust-1",
            List.of(nameRule()), Map.of("name", "Alice"), null);
        // Same row, same data, called again -- the cascade now finds this row's own key row as an
        // existing candidate and resolves to the SAME entity_id it already carries (no natural
        // change, so the "never silently recycle" guard is not even triggered: naturalEntityId
        // equals priorResolvedEntityId).
        ResolutionResult second = resolver.resolve(tenant, entityType, "erp", "cust-1",
            List.of(nameRule()), Map.of("name", "Alice"), null);

        assertThat(second.entityId()).isEqualTo(first.entityId());

        assertThat(markCount(tenant, indexName, first.entityId().toString()))
            .as("both calls attempt to mark the SAME canonical_key -- the second INSERT ... ON "
              + "CONFLICT (tenant_id, search_index, mark_kind, canonical_key) DO NOTHING collapses "
              + "into the row the first call already produced, exactly the mechanism Phase 2 relied "
              + "on for trigger 4a's own idempotency")
            .isEqualTo(1L);
        // No "previous" mark either call: finalEntityId never differed from priorResolvedEntityId.
        assertThat(totalMarkCount(tenant)).isEqualTo(1L);
    }

    @Test
    void aTenantWithNoSearchIndexOverTheEntityProducesZeroMarks() throws Exception {
        long tenant = 701_504;
        String entityName = "SearchRes701D" + System.nanoTime();
        String table = "t_search_res_701d_" + System.nanoTime();
        applyResolutionEntity(adminDs, tenant, entityName, table);
        // Deliberately: no SearchIndex applied over this entity.

        String entityType = entityName.toLowerCase(Locale.ROOT);
        ResolutionResult result = resolver.resolve(tenant, entityType, "erp", "cust-1",
            List.of(nameRule()), Map.of("name", "Alice"), null);

        assertThat(result.state()).isEqualTo(EntityResolutionState.RESOLVED);
        assertThat(result.newlyMinted()).isTrue();
        assertThat(totalMarkCount(tenant))
            .as("marking is purely additive, never a gate on resolution itself -- the entity still "
              + "resolves normally with zero SearchIndex declarations over it")
            .isZero();
    }

    private UUID identityRefOf(long tenant, String source, String sourceId) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT identity_ref FROM core.entity_identity WHERE tenant_id = ? AND source = ? AND source_id = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, source);
            ps.setString(3, sourceId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }
}
