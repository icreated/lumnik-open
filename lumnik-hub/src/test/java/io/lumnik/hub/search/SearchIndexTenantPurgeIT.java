package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;

import static io.lumnik.hub.search.SearchIndexItSupport.entity;
import static io.lumnik.hub.search.SearchIndexItSupport.indexRows;
import static io.lumnik.hub.search.SearchIndexItSupport.purgeRows;
import static io.lumnik.hub.search.SearchIndexItSupport.searchIndex;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #701 trigger row 7 (design §2/§4, tests §7-8): {@code TenantResource.deactivate} fires
 * {@code TenantArchived} synchronously, BEFORE {@code Tenant.archive()}, and
 * {@code TenantArchivedSearchPurgeListener} purges every SearchIndex the tenant owns — the one
 * #701 listener that does NOT catch-and-log, because a tenant delete has no later trigger to
 * repair a missed purge.
 */
@QuarkusTest
class SearchIndexTenantPurgeIT {

    private static final String ENTITY = "SearchTenantPurge701";
    private static final String TABLE = "t_searchtenantpurge701_erp";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static RequestSpecification yaml() {
        return given().config(YamlBody.CONFIG).contentType("application/yaml");
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    @BeforeEach
    void applyEntity() throws Exception {
        yaml().headers(TestIdentity.tenantAndUser(1))
            .body(entity(adminDs, ENTITY, TABLE, List.of("sales"), "code", "name"))
            .post("/api/entities/apply")
            .then().statusCode(200);
    }

    @AfterEach
    void clearRoles() {
        CurrentRolesHolder.clear();
    }

    /** Creates a tenant through identity's own door and returns its generated id. */
    private static long createTenant(String code) {
        CurrentRolesHolder.set(Set.of("lm_superadmin"));
        try {
            Number id = given().contentType("application/json")
                .body("{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}")
                .post("/api/tenants")
                .then().statusCode(201)
                .extract().path("id");
            return id.longValue();
        } finally {
            CurrentRolesHolder.clear();
        }
    }

    private static void deactivateTenant(long id, int expectedStatus) {
        CurrentRolesHolder.set(Set.of("lm_superadmin"));
        try {
            given().delete("/api/tenants/" + id).then().statusCode(expectedStatus);
        } finally {
            CurrentRolesHolder.clear();
        }
    }

    private static boolean tenantActive(long id) {
        CurrentRolesHolder.set(Set.of("lm_superadmin"));
        try {
            return given().get("/api/tenants/" + id).then().statusCode(200).extract().path("active");
        } finally {
            CurrentRolesHolder.clear();
        }
    }

    private static void applyIndex(long tenantId, String name) {
        yaml().headers(TestIdentity.tenantAndUser(tenantId))
            .body(searchIndex(name, ENTITY, "name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
    }

    private Timestamp archivedAt(long tenantId) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT archived_at FROM identity.tenant WHERE id = ?")) {
            ps.setLong(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("tenant row still exists (archive keeps the row)").isTrue();
                return rs.getTimestamp(1);
            }
        }
    }

    @Test
    void deletingATenantPurgesOnlyItsOwnDeclarations() throws Exception {
        long tenantA = createTenant(unique("purgeA"));
        long tenantB = createTenant(unique("purgeB"));
        String nameA = unique("idx-a");
        String nameB = unique("idx-b");
        applyIndex(tenantA, nameA);
        applyIndex(tenantB, nameB);

        deactivateTenant(tenantA, 204);

        assertThat(indexRows(adminDs, tenantA, nameA)).as("tenant A's declaration is gone").isZero();
        assertThat(purgeRows(adminDs, tenantA, nameA)).as("tenant A's purge obligation").isEqualTo(1);
        assertThat(indexRows(adminDs, tenantB, nameB)).as("tenant B untouched").isEqualTo(1);
        assertThat(purgeRows(adminDs, tenantB, nameB)).as("no purge for tenant B").isZero();
        assertThat(archivedAt(tenantA)).as("tenant A is archived").isNotNull();
    }

    @Test
    void deletingATenantWithNoDeclarationsSucceedsAndPurgesNothing() throws Exception {
        long tenant = createTenant(unique("empty"));

        deactivateTenant(tenant, 204);

        assertThat(archivedAt(tenant)).as("tenant is archived").isNotNull();
    }

    /**
     * The "no catch-and-log" half of the contract: an injected failure inside
     * {@code purgeAllForTenant} must propagate all the way out of {@code deactivate}, rolling back
     * the archive — the tenant must come back still active, and its declaration must still be
     * present. Same poison-trigger idiom as {@code SearchIndexDeleteIT.aFailedPurgeWriteRollsTheWholeDeleteBack}.
     */
    @Test
    void aFailedPurgeRollsBackTheArchive() throws Exception {
        long tenant = createTenant(unique("poison"));
        String name = unique("poisoned-idx");
        applyIndex(tenant, name);

        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE OR REPLACE FUNCTION core.search_index_purge_poison_701() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.tenant_id = " + tenant + " THEN RAISE EXCEPTION 'injected tenant purge failure'; END IF; "
                + "RETURN NEW; END $$");
            st.execute("CREATE TRIGGER search_index_purge_poison_701 BEFORE INSERT ON core.search_index_purge "
                + "FOR EACH ROW EXECUTE FUNCTION core.search_index_purge_poison_701()");
        }
        try {
            CurrentRolesHolder.set(Set.of("lm_superadmin"));
            int status;
            try {
                status = given().delete("/api/tenants/" + tenant).then().extract().statusCode();
            } finally {
                CurrentRolesHolder.clear();
            }
            assertThat(status).as("the deactivate must fail, not report success").isGreaterThanOrEqualTo(500);

            assertThat(archivedAt(tenant)).as("archive rolled back — tenant still active").isNull();
            assertThat(tenantActive(tenant)).as("GET confirms the tenant is still active").isTrue();
            assertThat(indexRows(adminDs, tenant, name)).as("declaration untouched").isEqualTo(1);
            assertThat(purgeRows(adminDs, tenant, name)).as("no purge obligation").isZero();
        } finally {
            try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP TRIGGER IF EXISTS search_index_purge_poison_701 ON core.search_index_purge");
                st.execute("DROP FUNCTION IF EXISTS core.search_index_purge_poison_701()");
            }
        }
        // Once the injection is gone, deactivating the same tenant goes through normally.
        deactivateTenant(tenant, 204);
        assertThat(purgeRows(adminDs, tenant, name)).isEqualTo(1);
    }

    /**
     * The reverse of {@link #aFailedPurgeRollsBackTheArchive}: the purge itself succeeds and
     * commits (the admin datasource is never enlisted in identity's JTA transaction — see
     * {@code TenantArchivedSearchPurgeListener}'s javadoc), and it is {@code Tenant.archive()}'s
     * OWN commit that then fails for an unrelated reason. This is the documented, accepted
     * compensating-semantics window (a review of this PR, #709, named it explicitly): the tenant
     * stays active, but its SearchIndex declarations are already gone and a purge obligation is
     * already recorded. Proves the window is real, characterizes exactly what it leaves behind,
     * and proves the self-healing recovery path: retrying the identical delete call purges
     * nothing a second time (idempotent) and simply completes the archive.
     */
    @Test
    void purgeSucceedsButArchiveItselfFailingLeavesTheTenantActiveWithDeclarationsAlreadyPurged() throws Exception {
        long tenant = createTenant(unique("archive-poison"));
        String name = unique("archive-poison-idx");
        applyIndex(tenant, name);

        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE OR REPLACE FUNCTION identity.tenant_archive_poison_701() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.id = " + tenant + " AND NEW.archived_at IS NOT NULL AND OLD.archived_at IS NULL "
                + "THEN RAISE EXCEPTION 'injected archive failure, after the purge already committed'; "
                + "END IF; RETURN NEW; END $$");
            st.execute("CREATE TRIGGER tenant_archive_poison_701 BEFORE UPDATE ON identity.tenant "
                + "FOR EACH ROW EXECUTE FUNCTION identity.tenant_archive_poison_701()");
        }
        try {
            CurrentRolesHolder.set(Set.of("lm_superadmin"));
            int status;
            try {
                status = given().delete("/api/tenants/" + tenant).then().extract().statusCode();
            } finally {
                CurrentRolesHolder.clear();
            }
            assertThat(status).as("the deactivate must fail, not report success").isGreaterThanOrEqualTo(500);

            assertThat(archivedAt(tenant))
                .as("archive() itself failed to commit -- tenant still active").isNull();
            assertThat(tenantActive(tenant)).as("GET confirms the tenant is still active").isTrue();
            assertThat(indexRows(adminDs, tenant, name))
                .as("the purge already committed before archive() ran -- the declaration is gone "
                  + "even though the tenant never actually got archived (the accepted window)")
                .isZero();
            assertThat(purgeRows(adminDs, tenant, name))
                .as("its purge obligation was recorded in the same, already-committed statement")
                .isEqualTo(1);
        } finally {
            try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP TRIGGER IF EXISTS tenant_archive_poison_701 ON identity.tenant");
                st.execute("DROP FUNCTION IF EXISTS identity.tenant_archive_poison_701()");
            }
        }
        // Self-healing: retrying the SAME call purges nothing new (the CTE finds no declarations
        // left) and simply completes the archive that failed the first time.
        deactivateTenant(tenant, 204);
        assertThat(archivedAt(tenant)).as("the retry completes the archive").isNotNull();
        assertThat(purgeRows(adminDs, tenant, name))
            .as("no duplicate purge obligation from the retry").isEqualTo(1);
    }
}
