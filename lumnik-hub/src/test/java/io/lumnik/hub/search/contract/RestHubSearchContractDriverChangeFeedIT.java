package io.lumnik.hub.search.contract;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionFieldMode;
import io.lumnik.hub.connector.event.ConnectorRunCompleted;
import io.lumnik.hub.search.projection.SearchProjectionWorker;
import io.lumnik.testkit.search.contract.CanonicalEntityDef;
import io.lumnik.testkit.search.contract.CanonicalFieldDef;
import io.lumnik.testkit.search.contract.CanonicalRow;
import io.lumnik.testkit.search.contract.RebuildOutcome;
import io.lumnik.testkit.search.contract.RebuildTerminalOutcome;
import io.lumnik.testkit.search.contract.TenantHandle;
import io.lumnik.testkit.search.contract.UserHandle;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(SearchBackendOidcProfile.class)
@EnabledIfSystemProperty(named = "lumnik.test.searchContractSuiteActive", matches = "true")
class RestHubSearchContractDriverChangeFeedIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject Event<ConnectorRunCompleted> connectorRunCompletedEvent;
    @Inject SearchProjectionWorker worker;

    @Test
    void reapplyingAnEntityWithAFewerColumnsDropsItFromFutureHits() {
        // Scoped to this entity, not unscoped (Set.of()) — matches every ISOLATION-adjacent test in
        // this suite's own convention of provisioning a bound user, even though tenantSeq is now
        // static and every test's tenant id is already unique (PR #734 review finding, P2).
        RestHubSearchContractDriver driver = new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);
        TenantHandle tenant = driver.provisionTenant();
        CanonicalEntityDef withSecret = new CanonicalEntityDef("contract_suite_task13_reapply", List.of(
            new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false),
            new CanonicalFieldDef("secret", null, false)));
        UserHandle user = driver.provisionUser(tenant, Set.of("scope:" + withSecret.name()));
        driver.applySearchIndex(tenant, "contract-suite-task13-reapply", withSecret, withSecret.columns());
        driver.ingestCanonicalRows(tenant, withSecret,
            List.of(new CanonicalRow("r13-1", Map.of("probe", "findme", "secret", "x"))));
        driver.awaitProjectionConvergence(tenant, "contract-suite-task13-reapply", Duration.ofSeconds(10));
        assertThat(driver.query(tenant, user, "findme", Map.of()).hits().get(0).fields()).containsKey("secret");

        CanonicalEntityDef withoutSecret = new CanonicalEntityDef("contract_suite_task13_reapply",
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        driver.reapplyEntity(tenant, withoutSecret);
        driver.awaitProjectionConvergence(tenant, "contract-suite-task13-reapply", Duration.ofSeconds(10));

        assertThat(driver.query(tenant, user, "findme", Map.of()).hits().get(0).fields()).doesNotContainKey("secret");
    }

    @Test
    void aResolutionMergeConvergesToOneRow() {
        // PR #734 review, round 3: a genuine identity-resolution merge needs TWO SOURCES, not two rows
        // of the same one. core.entity_identity's own uq_entity_identity_cardinality constraint ("at
        // most one identity per source") makes two rows of the SAME source converging onto one
        // entity_id structurally impossible — confirmed empirically (a raw, unhandled PSQLException,
        // filed as a follow-up issue against production, out of #674's own scope). The crosswalk is a
        // multi-source golden-record mechanism, never an intra-source dedup one, so this test now
        // drives a second, independent physical source (ingestIntoSecondSource) and merges ACROSS the
        // two via #619's own "same" assertion door — the ONE production-sanctioned way to converge two
        // already-resolved identities (a cascade-field mutation hits CascadeResolver's "never silently
        // recycle a resolved link" guard instead, confirmed empirically in an earlier round: refused as
        // "ambiguous", never "resolved").
        RestHubSearchContractDriver driver = new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);
        TenantHandle tenant = driver.provisionTenant();
        CanonicalEntityDef entity = new CanonicalEntityDef("contract_suite_task13_merge",
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        UserHandle user = driver.provisionUser(tenant, Set.of("scope:" + entity.name()));
        driver.applySearchIndex(tenant, "contract-suite-task13-merge", entity, entity.columns());
        driver.ensureResolutionEntityApplied(tenant, entity);
        driver.ingestCanonicalRows(tenant, entity, List.of(
            new CanonicalRow("m13-1", Map.of("probe", "findme")),
            new CanonicalRow("m13-2", Map.of("probe", "findme"))));
        driver.awaitProjectionConvergence(tenant, "contract-suite-task13-merge", Duration.ofSeconds(10));
        assertThat(driver.query(tenant, user, "findme", Map.of()).hits()).hasSize(2);

        // A second, wholly independent source, one row of its own — genuinely a different source_id
        // AND a different `source`, so its own crosswalk entry is a truly distinct identity, not
        // merely a second row of the same physical table.
        driver.ingestIntoSecondSource(tenant, entity, "alt-1");

        String mainSource = RestHubSearchContractDriver.tableName(entity);
        String altSource = driver.secondSourceTableName(entity);
        String resolutionManifestName = driver.resolutionEntityName(entity);
        RestHubSearchContractDriver.CrosswalkEntry beforeMain = driver.crosswalkEntry(tenant, resolutionManifestName, mainSource, "m13-1");
        RestHubSearchContractDriver.CrosswalkEntry beforeAlt = driver.crosswalkEntry(tenant, resolutionManifestName, altSource, "alt-1");
        assertThat(beforeMain).as("m13-1 must already carry its own resolved identity before any merge").isNotNull();
        assertThat(beforeAlt).as("alt-1 must already carry its own resolved identity before any merge").isNotNull();
        assertThat(beforeMain.state()).isEqualTo("resolved");
        assertThat(beforeAlt.state()).isEqualTo("resolved");
        assertThat(beforeMain.entityId())
            .as("the two identities must start genuinely distinct for the merge below to mean anything")
            .isNotEqualTo(beforeAlt.entityId());

        // The genuine merge: a human "same" assertion between two PRE-EXISTING, cross-source
        // identities — safe here (cardinality forbids only two rows of the SAME source sharing an
        // entity_id, never two of different sources). Recording the assertion alone does not
        // retroactively rewrite alt-1's crosswalk row — CascadeResolver only consults it at pass-time
        // — so reprocessSecondSourceRow re-fires resolution over it afterward.
        driver.assertSameIdentity(tenant, resolutionManifestName, beforeAlt.identityRef(), beforeMain.identityRef());
        driver.reprocessSecondSourceRow(tenant, entity, "alt-1");

        RestHubSearchContractDriver.CrosswalkEntry afterMain = driver.crosswalkEntry(tenant, resolutionManifestName, mainSource, "m13-1");
        RestHubSearchContractDriver.CrosswalkEntry afterAlt = driver.crosswalkEntry(tenant, resolutionManifestName, altSource, "alt-1");
        assertThat(afterAlt.state()).isEqualTo("resolved");
        assertThat(afterAlt.entityId())
            .as("EntityResolutionPass must have genuinely converged both PRE-EXISTING, cross-source "
                + "identities onto the same entity_id, driven by the human assertion — not merely "
                + "resolved alt-1 in isolation")
            .isEqualTo(afterMain.entityId());

        // The change-feed-observable half of this #701 trigger (design spec §8) — a SEPARATE,
        // driver-only bookkeeping mechanism (rowKey rewrite via rewriteMatchKey), independent
        // of the crosswalk merge just proved above.
        driver.rewriteMatchKey(tenant, entity, "m13-1", "m13-2");
        driver.awaitProjectionConvergence(tenant, "contract-suite-task13-merge", Duration.ofSeconds(10));
        assertThat(driver.query(tenant, user, "findme", Map.of()).hits()).hasSize(1);
    }

    @Test
    void aResolutionSplitRevertsAMergeBackToTwoDistinctIdentities() {
        // Mirrors aResolutionMergeConvergesToOneRow's own two-source setup and merge
        // (icreated/lumnik#737): proves the crosswalk itself, not just the search index, shows two
        // distinct entity_ids again after retracting the same assertion that produced the merge.
        RestHubSearchContractDriver driver = new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);
        TenantHandle tenant = driver.provisionTenant();
        CanonicalEntityDef entity = new CanonicalEntityDef("contract_suite_task737_split",
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        driver.applySearchIndex(tenant, "contract-suite-task737-split", entity, entity.columns());
        driver.ensureResolutionEntityApplied(tenant, entity);
        driver.ingestCanonicalRows(tenant, entity, List.of(new CanonicalRow("s737-1", Map.of("probe", "findme"))));
        driver.ingestIntoSecondSource(tenant, entity, "alt-1");

        String mainSource = RestHubSearchContractDriver.tableName(entity);
        String altSource = driver.secondSourceTableName(entity);
        String resolutionManifestName = driver.resolutionEntityName(entity);
        RestHubSearchContractDriver.CrosswalkEntry beforeMain =
            driver.crosswalkEntry(tenant, resolutionManifestName, mainSource, "s737-1");
        RestHubSearchContractDriver.CrosswalkEntry beforeAlt =
            driver.crosswalkEntry(tenant, resolutionManifestName, altSource, "alt-1");
        assertThat(beforeMain.entityId())
            .as("the two identities must start genuinely distinct for the merge below to mean anything")
            .isNotEqualTo(beforeAlt.entityId());

        driver.assertSameIdentity(tenant, resolutionManifestName, beforeAlt.identityRef(), beforeMain.identityRef());
        driver.reprocessSecondSourceRow(tenant, entity, "alt-1");

        RestHubSearchContractDriver.CrosswalkEntry merged =
            driver.crosswalkEntry(tenant, resolutionManifestName, altSource, "alt-1");
        assertThat(merged.entityId())
            .as("the merge must have genuinely converged onto beforeMain's entity before the split below means anything")
            .isEqualTo(beforeMain.entityId());

        // The retraction: the same pair, now asserted distinct — production's own upsertAssertion
        // ON CONFLICT flips the stored verdict in place, no separate "delete" door needed.
        driver.assertDistinctIdentity(tenant, resolutionManifestName, beforeAlt.identityRef(), beforeMain.identityRef());
        driver.reprocessSecondSourceRow(tenant, entity, "alt-1");

        RestHubSearchContractDriver.CrosswalkEntry afterMain =
            driver.crosswalkEntry(tenant, resolutionManifestName, mainSource, "s737-1");
        RestHubSearchContractDriver.CrosswalkEntry afterAlt =
            driver.crosswalkEntry(tenant, resolutionManifestName, altSource, "alt-1");
        assertThat(afterAlt.state()).isEqualTo("resolved");
        assertThat(afterAlt.entityId())
            .as("the moved row must land on a fresh identity, never back on the entity it just left")
            .isNotNull()
            .isNotEqualTo(afterMain.entityId());
        // The anchor is untouched.
        assertThat(afterMain.entityId()).isEqualTo(beforeMain.entityId());
    }

    @Test
    void deletingACanonicalRowDropsItFromFutureHitsViaTheRebuildFallback() {
        // The regression test for the STALE_CANONICAL_ROWS gap (icreated/lumnik#732): a bare SQL
        // DELETE is never observed by SearchProjectionRunHook's/SearchProjectionReconciler's
        // `_ingested_at > watermark` scan (proved by this task's own scratch verification, not
        // committed) — deleteCanonicalRow sets pendingHardDeleteFallback, which
        // awaitProjectionConvergence uses to fall back to a full triggerRebuild/awaitRebuildCompletion
        // cycle instead of trusting the incremental discoveryTick() drain. This proves that fallback
        // actually removes the row from live results, not merely that the call completes.
        //
        // Stale note (pre-3c784f1b) removed: ingestCanonicalRows now persists every row's rowKey in
        // its own dedicated ROW_KEY_COLUMN column, and deleteCanonicalRow matches on that column, not
        // on "probe" — so the row key here no longer needs to equal the probe column's own value.
        // Kept as "findme" for both purely for this test's own readability.
        //
        // The query user below is bound to THIS entity's own scope tag (applyEntityManifest always
        // declares "scopes: [entity.name()]"), never left unscoped — matches every other test in this
        // class (see reapplyingAnEntityWithAFewerColumnsDropsItFromFutureHits' identical note).
        // tenantSeq is static (PR #734 review finding, P2), so every test's tenant id is already
        // unique; scoping here is defense-in-depth for this test's own strict isEmpty(), not a
        // workaround for cross-test tenant collision.
        RestHubSearchContractDriver driver = new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);
        TenantHandle tenant = driver.provisionTenant();
        CanonicalEntityDef entity = new CanonicalEntityDef("contract_suite_task13_delete",
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        UserHandle user = driver.provisionUser(tenant, Set.of("scope:" + entity.name()));
        driver.applySearchIndex(tenant, "contract-suite-task13-delete", entity, entity.columns());
        driver.ingestCanonicalRows(tenant, entity, List.of(new CanonicalRow("findme", Map.of("probe", "findme"))));
        driver.awaitProjectionConvergence(tenant, "contract-suite-task13-delete", Duration.ofSeconds(10));
        assertThat(driver.query(tenant, user, "findme", Map.of()).hits()).isNotEmpty();

        driver.deleteCanonicalRow(tenant, entity, "findme");
        driver.awaitProjectionConvergence(tenant, "contract-suite-task13-delete", Duration.ofSeconds(30));

        assertThat(driver.query(tenant, user, "findme", Map.of()).hits()).isEmpty();
    }

    @Test
    void aHardDeletedRowSurvivesTheIncrementalDrainAndOnlyARebuildRemovesIt() throws SQLException {
        // Characterization of icreated/lumnik#732 — deliberately PINS a limitation. The projection follows
        // what ingestion writes (`_ingested_at > watermark`), so an out-of-band hard DELETE on a
        // connector.t_* table is invisible to the incremental drain and only a completed rebuild removes
        // the row. If deletion detection is added to the event-triggered producer or the drain, this test goes red (a
        // detection added only to the scheduled reconciler sweep is not exercised here) and the docs
        // (docs/search/search-indexes.md, "Rows deleted directly in the database") must change with it.
        //
        // The drain below is worker.discoveryTick() called DIRECTLY, never awaitProjectionConvergence():
        // deleteCanonicalRow sets pendingHardDeleteFallback, which awaitProjectionConvergence consumes to
        // trigger the very rebuild this first assertion excludes. The flag stays set afterwards — harmless,
        // the driver is per-test and the rebuild below is driven explicitly.
        RestHubSearchContractDriver driver = new RestHubSearchContractDriver(adminDs, connectorRunCompletedEvent, worker);
        TenantHandle tenant = driver.provisionTenant();
        CanonicalEntityDef entity = new CanonicalEntityDef("contract_suite_task732_pin",
            List.of(new CanonicalFieldDef("probe", ProjectionFieldMode.TEXT, false)));
        UserHandle user = driver.provisionUser(tenant, Set.of("scope:" + entity.name()));
        String index = "contract-suite-task732-pin";
        driver.applySearchIndex(tenant, index, entity, entity.columns());
        driver.ingestCanonicalRows(tenant, entity, List.of(new CanonicalRow("pin-1", Map.of("probe", "findme"))));
        driver.awaitProjectionConvergence(tenant, index, Duration.ofSeconds(10));
        assertThat(driver.query(tenant, user, "findme", Map.of()).hits()).hasSize(1);
        assertThat(backingRowCount(tenant, entity)).isEqualTo(1);

        driver.deleteCanonicalRow(tenant, entity, "pin-1");
        assertThat(backingRowCount(tenant, entity))
            .as("the DELETE must genuinely have removed the row from the backing table — otherwise the "
                + "assertion below proves nothing (the original #732 scratch check deleted 0 rows)")
            .isZero();

        for (int i = 0; i < 3; i++) {
            worker.discoveryTick();
        }
        assertThat(driver.query(tenant, user, "findme", Map.of()).hits())
            .as("#732: the incremental drain does not observe a hard delete — the row is still returned")
            .hasSize(1);

        RebuildOutcome triggered = driver.triggerRebuild(tenant, index);
        assertThat(triggered).isInstanceOf(RebuildOutcome.Started.class);
        assertThat(driver.awaitRebuildCompletion(tenant, index, triggered, Duration.ofSeconds(30)))
            .isEqualTo(RebuildTerminalOutcome.READY);
        assertThat(driver.query(tenant, user, "findme", Map.of()).hits())
            .as("a completed rebuild removes the deleted row")
            .isEmpty();
    }

    private long backingRowCount(TenantHandle tenant, CanonicalEntityDef entity) throws SQLException {
        String sql = "SELECT count(*) FROM connector." + RestHubSearchContractDriver.tableName(entity)
            + " WHERE tenant_id = ?";
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tenant.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
