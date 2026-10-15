package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static io.lumnik.hub.search.SearchIndexItSupport.entity;
import static io.lumnik.hub.search.SearchIndexItSupport.searchIndex;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * #701 phase 2, triggers 4a ("kind: SearchIndex applied/changed") and 6 ("entity deleted"): the
 * design doc §4 rows this phase wires. Trigger 4a's marks are read straight off
 * {@code core.search_projection_mark} (admin pool, RLS bypassed — the oracle, never the thing under
 * test, same posture as every other SearchIndex IT). Trigger 6 is read off
 * {@code core.search_index_purge}, the same obligation {@code deleteWithPurge} already writes for a
 * single declaration (#669), now fanned out across tenants by {@code purgeAllForEntity}.
 */
@QuarkusTest
class SearchProjectionMarkTriggerIT {

    private static final String ENTITY = "SearchMarkTrigger701";
    private static final String TABLE = "t_searchmarktrigger701_erp";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static RequestSpecification yaml(long tenant) {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/yaml");
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private long allMarks(long tenant, String name, String reason) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_projection_mark "
               + "WHERE tenant_id = ? AND search_index = ? AND mark_kind = 'ALL' AND reason = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.setString(3, reason);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long purgeRows(long tenant, String name, String entity) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_index_purge "
               + "WHERE tenant_id = ? AND name = ? AND entity = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            ps.setString(3, entity);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long indexRows(long tenant, String name) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM core.search_index WHERE tenant_id = ? AND name = ?")) {
            ps.setLong(1, tenant);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // -- Trigger 4a: kind: SearchIndex applied/changed --------------------------------------------

    @Test
    void aFreshApplyProducesExactlyOneAllMark() throws Exception {
        String entityName = ENTITY + "Fresh";
        String table = TABLE + "_fresh";
        yaml(1).body(entity(adminDs, entityName, table, List.of("sales"), "code", "name", "city"))
            .post("/api/entities/apply").then().statusCode(200);

        String name = unique("mark-fresh");
        yaml(1).body(searchIndex(name, entityName, "name", "city"))
            .post("/api/search-indexes/apply").then().statusCode(200);

        assertThat(allMarks(1, name, "index-apply")).isEqualTo(1L);
    }

    /**
     * A re-apply that only changes {@code language} is still {@code APPLIED} per
     * {@link SearchIndexStore.ApplyOutcome}. Both applies write an {@code ALL} mark for the same
     * {@code (tenant, search_index)} pair; {@code canonical_key = ''} for every {@code ALL} mark, so
     * the UNIQUE constraint {@code (tenant_id, search_index, mark_kind, canonical_key)} makes the
     * second apply's {@code INSERT ... ON CONFLICT DO NOTHING} collide with the first and do nothing.
     * That is the true behaviour, not a guess (traced against V56's schema): the two applies
     * collapse into ONE row, matching design doc §2's idempotency row — "a second identical mark for
     * work not yet consumed collapses into the existing row".
     */
    @Test
    void aReapplyThatOnlyChangesLanguageCollapsesToOneAllMark() throws Exception {
        String entityName = ENTITY + "Relang";
        String table = TABLE + "_relang";
        yaml(1).body(entity(adminDs, entityName, table, List.of("sales"), "code", "name", "city"))
            .post("/api/entities/apply").then().statusCode(200);

        String name = unique("mark-relang");
        yaml(1).body(searchIndex(name, entityName, "name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        yaml(1).body(searchIndex(name, entityName, "name").replace("  entity:", "  language: french\n  entity:"))
            .post("/api/search-indexes/apply").then().statusCode(200);

        assertThat(allMarks(1, name, "index-apply"))
            .as("both applies mark the same (tenant, search_index, ALL, '') tuple — the second collides "
              + "with the first under ON CONFLICT DO NOTHING and is not a new row")
            .isEqualTo(1L);
    }

    // -- Trigger 6: entity deleted ------------------------------------------------------------------

    @Test
    void entityDeletePurgesEveryTenantsSearchIndexOverIt() throws Exception {
        String entityName = ENTITY + "Delete";
        String table = TABLE + "_delete";
        yaml(1).body(entity(adminDs, entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);

        String name1 = unique("mark-delete-t1");
        String name2 = unique("mark-delete-t2");
        yaml(1).body(searchIndex(name1, entityName, "name")).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));
        yaml(2).body(searchIndex(name2, entityName, "name")).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));

        given().headers(TestIdentity.tenantAndUser(1)).delete("/api/entities/" + entityName)
            .then().statusCode(204);

        assertThat(purgeRows(1, name1, entityName)).as("tenant 1's declaration purged").isEqualTo(1L);
        assertThat(purgeRows(2, name2, entityName)).as("tenant 2's declaration purged").isEqualTo(1L);
        assertThat(indexRows(1, name1)).as("tenant 1's declaration gone").isZero();
        assertThat(indexRows(2, name2)).as("tenant 2's declaration gone").isZero();
    }

    /**
     * The atomicity this trigger's purge and the entity's own DROP VIEW/manifest delete now share
     * (a review of this PR, #709, found the previous version committing the purge on its own,
     * separate admin-DS transaction, ahead of {@code EntityViewCompiler.delete}'s — leaving a
     * window where a later DROP/DELETE failure left the purge landed with the entity never actually
     * gone). The purge now runs LAST, inside {@code EntityViewCompiler.delete}'s own connection
     * (its {@code ConnectionWork} overload): poisoning the purge INSERT itself must roll back the
     * DROP VIEW and manifest delete that already ran ahead of it in the SAME transaction — the
     * entity must come back exactly as if the whole delete never happened. Same poison-trigger
     * idiom as {@code SearchIndexDeleteIT.aFailedPurgeWriteRollsTheWholeDeleteBack}.
     */
    @Test
    void aFailedPurgeDuringEntityDeleteRollsBackTheEntityDeleteToo() throws Exception {
        String entityName = ENTITY + "DeleteAtomic";
        String table = TABLE + "_delete_atomic";
        yaml(1).body(entity(adminDs, entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);

        String name = unique("mark-delete-atomic");
        yaml(1).body(searchIndex(name, entityName, "name")).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));

        try (Connection c = adminDs.getConnection(); var st = c.createStatement()) {
            // core.search_index.entity (and therefore the purge row's own entity column) carries the
            // ORIGINAL manifest casing, not lower(name) -- entity_manifest.name is what's stored
            // lowercase, not this copy. Compare case-insensitively, the same convention
            // SearchIndexStore's own WHERE lower(entity) = lower(?) already uses.
            st.execute("CREATE OR REPLACE FUNCTION core.search_index_purge_entity_poison_701() "
                + "RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF lower(NEW.entity) = lower('" + entityName + "') THEN "
                + "  RAISE EXCEPTION 'injected purge failure during entity delete'; END IF; "
                + "RETURN NEW; END $$");
            st.execute("CREATE TRIGGER search_index_purge_entity_poison_701 BEFORE INSERT "
                + "ON core.search_index_purge "
                + "FOR EACH ROW EXECUTE FUNCTION core.search_index_purge_entity_poison_701()");
        }
        try {
            int status = given().headers(TestIdentity.tenantAndUser(1)).delete("/api/entities/" + entityName)
                .then().extract().statusCode();
            assertThat(status).as("the entity delete must fail, not report success").isGreaterThanOrEqualTo(500);

            assertThat(indexRows(1, name)).as("the SearchIndex declaration survives -- rolled back with it")
                .isEqualTo(1L);
            assertThat(purgeRows(1, name, entityName)).as("no purge obligation from the failed attempt")
                .isZero();
            try (Connection c = adminDs.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM connector.entity_manifest WHERE name = lower(?)")) {
                ps.setString(1, entityName);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1))
                        .as("the entity itself is still there -- DROP VIEW/manifest delete rolled back "
                          + "together with the purge that failed after them, in the same transaction")
                        .isEqualTo(1L);
                }
            }
        } finally {
            try (Connection c = adminDs.getConnection(); var st = c.createStatement()) {
                st.execute("DROP TRIGGER IF EXISTS search_index_purge_entity_poison_701 "
                    + "ON core.search_index_purge");
                st.execute("DROP FUNCTION IF EXISTS core.search_index_purge_entity_poison_701()");
            }
        }
        // Once the injection is gone, deleting the same entity goes through normally.
        given().headers(TestIdentity.tenantAndUser(1)).delete("/api/entities/" + entityName)
            .then().statusCode(204);
        assertThat(purgeRows(1, name, entityName)).isEqualTo(1L);
    }

    @Test
    void entityDeleteWithNoSearchIndexPurgesNothing() throws Exception {
        String entityName = ENTITY + "DeleteBare";
        String table = TABLE + "_delete_bare";
        yaml(1).body(entity(adminDs, entityName, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);

        given().headers(TestIdentity.tenantAndUser(1)).delete("/api/entities/" + entityName)
            .then().statusCode(204);
        // No SearchIndex ever existed over this entity — purgeAllForEntity is a no-op, and the delete
        // still succeeds (design doc: closing this hole must never refuse the entity delete).
    }
}
