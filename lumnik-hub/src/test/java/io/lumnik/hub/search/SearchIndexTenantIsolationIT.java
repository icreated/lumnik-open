package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.lumnik.hub.search.SearchIndexItSupport.entity;
import static io.lumnik.hub.search.SearchIndexItSupport.indexRows;
import static io.lumnik.hub.search.SearchIndexItSupport.purgeRows;
import static io.lumnik.hub.search.SearchIndexItSupport.searchIndex;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * #669 tenant isolation under {@link RlsEnforcedProfile} — the only profile where the default
 * datasource does not own the tables, so a missing {@code @Transactional} (SET LOCAL never fired)
 * or a store filtering on the wrong tenant actually shows. The same name lives independently in two
 * tenants: B's apply creates its own row, B's delete touches nothing of A's, and each purge
 * obligation belongs to the tenant that asked for it.
 *
 * <p>Entity manifests are deployment-global ({@code connector.entity_manifest} has no tenant), so
 * each tenant here declares its SearchIndex over its own entity only to tell the two rows apart.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class SearchIndexTenantIsolationIT {

    private static final String SHARED_NAME = "shared-search-index-669";
    private static final String ENTITY_A = "SearchTenantA669";
    private static final String ENTITY_B = "SearchTenantB669";

    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private static RequestSpecification yaml(long tenant) {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(tenant))
            .contentType("application/yaml");
    }

    @Test
    void theSameNameLivesIndependentlyInTwoTenants() throws Exception {
        yaml(1).body(entity(adminDs, ENTITY_A, "t_searchtenant669_a", List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);
        yaml(2).body(entity(adminDs, ENTITY_B, "t_searchtenant669_b", List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);

        // 1) Tenant A declares the name first.
        yaml(1).body(searchIndex(SHARED_NAME, ENTITY_A, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200);

        // 2) Tenant B's apply of the same name must CREATE B's own row, not collide or overwrite.
        yaml(2).body(searchIndex(SHARED_NAME, ENTITY_B, "name"))
            .post("/api/search-indexes/apply").then().statusCode(200)
            .body("entity", equalTo(ENTITY_B));
        assertThat(indexRows(adminDs, 1, SHARED_NAME)).isEqualTo(1);
        assertThat(indexRows(adminDs, 2, SHARED_NAME)).isEqualTo(1);

        // 3) Each tenant sees exactly its own declaration.
        given().headers(TestIdentity.tenantAndUser(1)).get("/api/search-indexes")
            .then().statusCode(200)
            .body("findAll { it.name == '" + SHARED_NAME + "' }", hasSize(1))
            .body("find { it.name == '" + SHARED_NAME + "' }.entity", equalTo(ENTITY_A));
        given().headers(TestIdentity.tenantAndUser(2)).get("/api/search-indexes")
            .then().statusCode(200)
            .body("findAll { it.name == '" + SHARED_NAME + "' }", hasSize(1))
            .body("find { it.name == '" + SHARED_NAME + "' }.entity", equalTo(ENTITY_B));

        // 4) B's delete removes B's row only, and the purge obligation is B's alone.
        given().headers(TestIdentity.tenantAndUser(2)).delete("/api/search-indexes/" + SHARED_NAME)
            .then().statusCode(204);
        assertThat(indexRows(adminDs, 2, SHARED_NAME)).isZero();
        assertThat(indexRows(adminDs, 1, SHARED_NAME)).as("tenant A untouched").isEqualTo(1);
        assertThat(purgeRows(adminDs, 2, SHARED_NAME)).isEqualTo(1);
        assertThat(purgeRows(adminDs, 1, SHARED_NAME)).as("no obligation leaked to tenant A").isZero();

        // 5) B cannot delete (nor see) A's declaration through its own tenant: it no longer has one.
        given().headers(TestIdentity.tenantAndUser(2)).delete("/api/search-indexes/" + SHARED_NAME)
            .then().statusCode(404);
        assertThat(indexRows(adminDs, 1, SHARED_NAME)).isEqualTo(1);
    }
}
