package io.lumnik.identity.org;

import io.lumnik.identity.tenant.Tenant;
import io.lumnik.identity.tenant.TenantRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class OrgResourceTest {

    @Inject TenantRepository tenants;
    @Inject OrgRepository orgs;

    private Long tenantId;

    @BeforeEach
    @Transactional
    void setup() {
        Tenant t = new Tenant("OTEST", "Org Test Tenant");
        tenants.persist(t);
        tenantId = t.getId();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        orgs.delete("tenantId = ?1", tenantId);
        tenants.delete("code = 'OTEST'");
    }

    @Test
    void createAndRetrieveOrg() {
        String id = given().contentType(ContentType.JSON)
            .body("""
                {"code": "HQ", "name": "Headquarters"}
                """)
            .when().post("/api/tenants/" + tenantId + "/orgs")
            .then()
            .statusCode(201)
            .body("code", equalTo("HQ"))
            .body("name", equalTo("Headquarters"))
            .body("active", equalTo(true))
            .body("tenantId", equalTo(tenantId.intValue()))
            .extract().path("id").toString();

        given().when().get("/api/tenants/" + tenantId + "/orgs/" + id)
            .then().statusCode(200)
            .body("id", equalTo(Integer.parseInt(id)));
    }

    @Test
    void duplicateOrgCodeInSameTenantIsRejected() {
        String body = """
            {"code": "DUP", "name": "Duplicate Org"}
            """;
        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/tenants/" + tenantId + "/orgs")
            .then().statusCode(201);

        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/tenants/" + tenantId + "/orgs")
            .then().statusCode(409);
    }

    @Test
    void blankCodeIsRejected() {
        given().contentType(ContentType.JSON)
            .body("""
                {"code": "", "name": "No Code Org"}
                """)
            .when().post("/api/tenants/" + tenantId + "/orgs")
            .then().statusCode(400);
    }

    @Test
    void blankNameIsRejected() {
        given().contentType(ContentType.JSON)
            .body("""
                {"code": "X", "name": ""}
                """)
            .when().post("/api/tenants/" + tenantId + "/orgs")
            .then().statusCode(400);
    }

    @Test
    void unknownOrgReturns404() {
        given().when().get("/api/tenants/" + tenantId + "/orgs/999999")
            .then().statusCode(404);
    }

    @Test
    void unknownTenantReturns404() {
        given().when().get("/api/tenants/999999/orgs")
            .then().statusCode(404);
    }

    @Test
    void orgNotFoundInWrongTenantReturns404() {
        String id = given().contentType(ContentType.JSON)
            .body("""
                {"code": "ISO", "name": "Isolated Org"}
                """)
            .when().post("/api/tenants/" + tenantId + "/orgs")
            .then().statusCode(201)
            .extract().path("id").toString();

        given().when().get("/api/tenants/999999/orgs/" + id)
            .then().statusCode(404);
    }

    @Test
    void deactivateOrg() {
        String id = given().contentType(ContentType.JSON)
            .body("""
                {"code": "TMP", "name": "Temp Org"}
                """)
            .when().post("/api/tenants/" + tenantId + "/orgs")
            .then().statusCode(201)
            .extract().path("id").toString();

        given().when().delete("/api/tenants/" + tenantId + "/orgs/" + id)
            .then().statusCode(204);

        given().when().get("/api/tenants/" + tenantId + "/orgs")
            .then().statusCode(200)
            .body("name", not(hasItem("Temp Org")));

        given().when().get("/api/tenants/" + tenantId + "/orgs/" + id)
            .then().statusCode(200)
            .body("active", equalTo(false));
    }

    @Test
    @Disabled("Cross-tenant uniqueness is enforced by Hibernate tenantFilter + RLS; " +
              "verified in TenantHubIsolationIT. Without the filter active in " +
              "module-level tests, codeExists() sees all tenants.")
    void sameCodeInDifferentTenantsIsAllowed() {
        Tenant other = new Tenant("OTEST2", "Other Tenant");
        long otherId = createTenant(other);
        try {
            given().contentType(ContentType.JSON)
                .body("""
                    {"code": "SHARE", "name": "Tenant A Org"}
                    """)
                .when().post("/api/tenants/" + tenantId + "/orgs")
                .then().statusCode(201);

            given().contentType(ContentType.JSON)
                .body("""
                    {"code": "SHARE", "name": "Tenant B Org"}
                    """)
                .when().post("/api/tenants/" + otherId + "/orgs")
                .then().statusCode(201);
        } finally {
            deleteTenant(otherId);
        }
    }

    @Transactional
    Long createTenant(Tenant t) {
        tenants.persist(t);
        return t.getId();
    }

    @Transactional
    void deleteTenant(Long id) {
        orgs.delete("tenantId = ?1", id);
        tenants.deleteById(id);
    }
}
