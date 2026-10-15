package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.auth.NoBypassOidcProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.lumnik.hub.search.SearchIndexItSupport.freeEntities;
import static io.lumnik.hub.search.SearchIndexItSupport.entity;
import static io.lumnik.hub.search.SearchIndexItSupport.indexRows;
import static io.lumnik.hub.search.SearchIndexItSupport.purgeRows;
import static io.lumnik.hub.search.SearchIndexItSupport.searchIndex;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * #669 scope posture under real bearer JWTs (dev-bypass off). Writes ask {@code allowsAll} over the
 * referenced entity's scopes — holding one of two is escalation, not access — and over the
 * previously stored entity's scopes on a re-apply or delete. The read asks {@code allowsAny}.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class SearchIndexScopeIT {

    /** Scoped [sales, magasin]: a write needs both. */
    private static final String SHARED = "SearchScopeShared669";
    /** Scoped [sales] only. */
    private static final String SALES = "SearchScopeSales669";
    /** Scoped [magasin] only. */
    private static final String MAGASIN = "SearchScopeMagasin669";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static String bearer(String... roles) {
        return "Bearer " + NoBypassOidcProfile.token(Set.of(roles), null);
    }

    private static String admin() { return bearer("lm_admin", "lm_integrator", "lm_user"); }
    private static String integratorSales() { return bearer("lm_integrator", "lm_user", "scope:sales"); }
    private static String integratorBoth() { return bearer("lm_integrator", "lm_user", "scope:sales", "scope:magasin"); }
    private static String integratorMagasin() { return bearer("lm_integrator", "lm_user", "scope:magasin"); }
    private static String userMagasin() { return bearer("lm_user", "scope:magasin"); }
    private static String userUnscoped() { return bearer("lm_user"); }

    private static RequestSpecification as(String token) {
        return given().config(YamlBody.CONFIG).header("Authorization", token).contentType("application/yaml");
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private void applyEntity(String name, String table, List<String> scopes) throws Exception {
        as(admin()).body(entity(adminDs, name, table, scopes, "code", "name"))
            .post("/api/entities/apply")
            .then().statusCode(200);
    }

    @BeforeEach
    void entities() throws Exception {
        freeEntities(adminDs, SHARED, SALES, MAGASIN);
        applyEntity(SHARED, "t_searchscope669_shared", List.of("sales", "magasin"));
        applyEntity(SALES, "t_searchscope669_sales", List.of("sales"));
        applyEntity(MAGASIN, "t_searchscope669_magasin", List.of("magasin"));
    }

    // --- apply ------------------------------------------------------------------------------

    @Test
    void holdingOneOfTheEntitysTwoScopesIsNotEnoughToApply() throws Exception {
        String name = unique("shared-partial");
        as(integratorSales()).body(searchIndex(name, SHARED, "name"))
            .post("/api/search-indexes/apply")
            .then().statusCode(403)
            .body("error", equalTo("scope not granted"));
        assertThat(indexRows(adminDs, 1, name)).isZero();
    }

    @Test
    void holdingEveryScopeOfTheEntityApplies() {
        as(integratorBoth()).body(searchIndex(unique("shared-full"), SHARED, "name"))
            .post("/api/search-indexes/apply")
            .then().statusCode(200);
    }

    @Test
    void aPlainUserCannotApply() {
        as(userUnscoped()).body(searchIndex(unique("user-apply"), SALES, "name"))
            .post("/api/search-indexes/apply")
            .then().statusCode(403);
    }

    @Test
    void aReApplyCannotTakeOverANameHeldOverAnEntityOutsideTheCallersGrants() throws Exception {
        String name = unique("takeover");
        as(admin()).body(searchIndex(name, SALES, "name")).post("/api/search-indexes/apply").then().statusCode(200);

        // The new target (MAGASIN) is inside the intruder's grants; the stored one (SALES) is not.
        as(integratorMagasin()).body(searchIndex(name, MAGASIN, "name"))
            .post("/api/search-indexes/apply")
            .then().statusCode(403)
            .body("error", equalTo("scope not granted"));

        as(admin()).get("/api/search-indexes")
            .then().statusCode(200)
            .body("find { it.name == '" + name + "' }.entity", equalTo(SALES));
    }

    /**
     * Another hub instance re-applied the entity into a scope the caller does not hold: the row in
     * connector.entity_manifest moved, this instance's EntityRegistry cache did not. The door must
     * decide on the stored definition, not on the cached copy.
     */
    @Test
    void anEntityMovedOutOfTheCallersScopesByAnotherInstanceIsRefusedDespiteAStaleCache() throws Exception {
        String entityName = "SearchScopeMoved669";
        String table = "t_searchscope669_moved";
        applyEntity(entityName, table, List.of("sales"));
        // This instance's cache now holds [sales]. Simulate the other instance's re-apply, DB only.
        String moved = SearchIndexItSupport.entityManifest(entityName, table, List.of("magasin"), "code", "name");
        assertThat(Jdbc.update(adminDs, "another instance moves the entity",
            "UPDATE connector.entity_manifest SET yaml_text = ?, scopes = ARRAY['magasin'], updated_at = now() "
                + "WHERE name = lower(?)",
            ps -> {
                ps.setString(1, moved);
                ps.setString(2, entityName);
            })).isEqualTo(1);

        String name = unique("stale-cache");
        as(integratorSales()).body(searchIndex(name, entityName, "name"))
            .post("/api/search-indexes/apply")
            .then().statusCode(403)
            .body("error", equalTo("scope not granted"));
        assertThat(indexRows(adminDs, 1, name)).isZero();
    }

    // --- read -------------------------------------------------------------------------------

    @Test
    void theListShowsOnlyIndexesOverEntitiesTheCallerMayRead() {
        String sales = unique("list-sales");
        String magasin = unique("list-magasin");
        String shared = unique("list-shared");
        as(admin()).body(searchIndex(sales, SALES, "name")).post("/api/search-indexes/apply").then().statusCode(200);
        as(admin()).body(searchIndex(magasin, MAGASIN, "name")).post("/api/search-indexes/apply").then().statusCode(200);
        as(admin()).body(searchIndex(shared, SHARED, "name")).post("/api/search-indexes/apply").then().statusCode(200);

        // Bound to magasin: reading needs an overlap (allowsAny), so SHARED is visible, SALES is not.
        as(userMagasin()).get("/api/search-indexes")
            .then().statusCode(200)
            .body("findAll { it.name == '" + sales + "' }", hasSize(0))
            .body("findAll { it.name == '" + magasin + "' }", hasSize(1))
            .body("findAll { it.name == '" + shared + "' }", hasSize(1));

        as(userUnscoped()).get("/api/search-indexes")
            .then().statusCode(200)
            .body("findAll { it.name == '" + sales + "' }", hasSize(1))
            .body("findAll { it.name == '" + magasin + "' }", hasSize(1))
            .body("findAll { it.name == '" + shared + "' }", hasSize(1));
    }

    // --- delete -----------------------------------------------------------------------------

    @Test
    void deleteNeedsEveryScopeOfTheStoredEntityAndARefusalWritesNoPurge() throws Exception {
        String name = unique("delete-partial");
        as(admin()).body(searchIndex(name, SHARED, "name")).post("/api/search-indexes/apply").then().statusCode(200);

        as(integratorSales()).delete("/api/search-indexes/" + name)
            .then().statusCode(403)
            .body("error", equalTo("scope not granted"));

        assertThat(indexRows(adminDs, 1, name)).as("refused delete keeps the declaration").isEqualTo(1);
        assertThat(purgeRows(adminDs, 1, name)).as("refused delete records no purge").isZero();

        as(integratorBoth()).delete("/api/search-indexes/" + name).then().statusCode(204);
        assertThat(indexRows(adminDs, 1, name)).isZero();
        assertThat(purgeRows(adminDs, 1, name)).isEqualTo(1);
    }

    @Test
    void aPlainUserCannotDelete() throws Exception {
        String name = unique("delete-user");
        as(admin()).body(searchIndex(name, SALES, "name")).post("/api/search-indexes/apply").then().statusCode(200);
        as(userUnscoped()).delete("/api/search-indexes/" + name).then().statusCode(403);
        assertThat(indexRows(adminDs, 1, name)).isEqualTo(1);
        assertThat(purgeRows(adminDs, 1, name)).isZero();
    }
}
