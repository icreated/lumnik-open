package io.lumnik.identity.role;

import io.lumnik.identity.tenant.Tenant;
import io.lumnik.identity.tenant.TenantRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RoleResourceTest {

    @Inject TenantRepository tenants;
    @Inject RoleRepository roles;

    private Long tenantId;

    @BeforeEach
    @Transactional
    void setup() {
        Tenant t = new Tenant("RTEST", "Role Test Tenant");
        tenants.persist(t);
        tenantId = t.getId();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        roles.delete("tenantId = ?1", tenantId);
        tenants.delete("code = 'RTEST'");
    }

    @Test
    void createAndRetrieveRole() {
        String id = given().contentType(ContentType.JSON)
            .body("""
                {"name": "Sales Manager", "userLevel": "CO"}
                """)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then()
            .statusCode(201)
            .body("name", equalTo("Sales Manager"))
            .body("userLevel", equalTo("CO"))
            .body("active", equalTo(true))
            .body("tenantId", equalTo(tenantId.intValue()))
            .extract().path("id").toString();

        given().when().get("/api/tenants/" + tenantId + "/roles/" + id)
            .then().statusCode(200)
            .body("id", equalTo(Integer.parseInt(id)));
    }

    @Test
    void defaultUserLevelIsClientOrg() {
        given().contentType(ContentType.JSON)
            .body("""
                {"name": "Default Level Role"}
                """)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(201)
            .body("userLevel", equalTo("CO"));
    }

    @Test
    void duplicateRoleNameInSameTenantIsRejected() {
        String body = """
            {"name": "Duplicate Role"}
            """;
        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(201);

        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(409);
    }

    @Test
    void invalidUserLevelIsRejected() {
        given().contentType(ContentType.JSON)
            .body("""
                {"name": "Bad Level", "userLevel": "X"}
                """)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(400);
    }

    @Test
    void deactivateRole() {
        String id = given().contentType(ContentType.JSON)
            .body("""
                {"name": "Temp Role"}
                """)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(201)
            .extract().path("id").toString();

        given().when().delete("/api/tenants/" + tenantId + "/roles/" + id)
            .then().statusCode(204);

        given().when().get("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(200)
            .body("name", not(hasItem("Temp Role")));
    }

    @Test
    void unknownTenantReturns404() {
        given().when().get("/api/tenants/999999/roles")
            .then().statusCode(404);
    }

    @Test
    void roleNotFoundInWrongTenantReturns404() {
        String id = given().contentType(ContentType.JSON)
            .body("""
                {"name": "Isolated Role"}
                """)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(201)
            .extract().path("id").toString();

        given().when().get("/api/tenants/999999/roles/" + id)
            .then().statusCode(404);
    }
}
