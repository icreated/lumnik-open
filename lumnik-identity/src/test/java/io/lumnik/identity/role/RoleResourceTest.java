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
import java.util.List;

@QuarkusTest
class RoleResourceTest {

    @Inject TenantRepository tenants;
    @Inject RoleRepository roles;

    private Long tenantId;
    private Long otherTenantId;

    @BeforeEach
    @Transactional
    void setup() {
        Tenant t = new Tenant("RTEST", "Role Test Tenant");
        tenants.persist(t);
        tenantId = t.getId();
        Tenant other = new Tenant("RTEST2", "Role Test Tenant 2");
        tenants.persist(other);
        otherTenantId = other.getId();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        roles.delete("tenantId in ?1", List.of(tenantId, otherTenantId));
        tenants.delete("code in ?1", List.of("RTEST", "RTEST2"));
    }

    /**
     * A role name is unique per tenant ({@code uq_role_name} is {@code (tenant_id, name)}), and the
     * lookup that names the holder is scoped to the path's tenant by itself, never by RLS alone:
     * unscoped, it refused a name free in this tenant and named another tenant's role.
     */
    @Test
    void sameRoleNameInDifferentTenantsIsAllowed() {
        given().contentType(ContentType.JSON)
            .body("""
                {"name": "Shared Name", "userLevel": "CO"}
                """)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(201);

        given().contentType(ContentType.JSON)
            .body("""
                {"name": "Shared Name", "userLevel": "CO"}
                """)
            .when().post("/api/tenants/" + otherTenantId + "/roles")
            .then().statusCode(201);
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
        String holder = given().contentType(ContentType.JSON).body(body)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(201)
            .extract().path("id").toString();

        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(409)
            .contentType(ContentType.JSON)
            .body("error", equalTo("role name Duplicate Role is already held by role " + holder));
    }

    /** The door stores the name stripped: a padded duplicate is the same name, and meets the 409. */
    @Test
    void aPaddedDuplicateNameIsRejectedLikeAnyOther() {
        String holder = given().contentType(ContentType.JSON).body("""
                {"name": "Padded Role"}
                """)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(201).extract().path("id").toString();

        given().contentType(ContentType.JSON).body("""
                {"name": "  Padded Role "}
                """)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(409)
            .contentType(ContentType.JSON)
            .body("error", equalTo("role name Padded Role is already held by role " + holder));
    }

    /** A rename is stripped as a create is: onto a taken name it is refused, onto its own name it is a no-op. */
    @Test
    void aPaddedRenameIsStrippedBeforeItIsChecked() {
        String holder = given().contentType(ContentType.JSON).body("""
                {"name": "Held Name"}
                """)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(201).extract().path("id").toString();
        String other = given().contentType(ContentType.JSON).body("""
                {"name": "Own Name"}
                """)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(201).extract().path("id").toString();

        given().contentType(ContentType.JSON).body("""
                {"name": " Held Name "}
                """)
            .when().put("/api/tenants/" + tenantId + "/roles/" + other)
            .then().statusCode(409)
            .body("error", equalTo("role name Held Name is already held by role " + holder));

        given().contentType(ContentType.JSON).body("""
                {"name": " Own Name "}
                """)
            .when().put("/api/tenants/" + tenantId + "/roles/" + other)
            .then().statusCode(200)
            .body("name", equalTo("Own Name"));
    }

    @Test
    void renamingARoleToATakenNameIsRejected() {
        String holder = given().contentType(ContentType.JSON)
            .body("""
                {"name": "Taken Role"}
                """)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(201)
            .extract().path("id").toString();
        String other = given().contentType(ContentType.JSON)
            .body("""
                {"name": "Other Role"}
                """)
            .when().post("/api/tenants/" + tenantId + "/roles")
            .then().statusCode(201)
            .extract().path("id").toString();

        given().contentType(ContentType.JSON)
            .body("""
                {"name": "Taken Role"}
                """)
            .when().put("/api/tenants/" + tenantId + "/roles/" + other)
            .then().statusCode(409)
            .contentType(ContentType.JSON)
            .body("error", equalTo("role name Taken Role is already held by role " + holder));
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
