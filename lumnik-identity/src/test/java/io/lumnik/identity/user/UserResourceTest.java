package io.lumnik.identity.user;

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

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class UserResourceTest {

    @Inject TenantRepository tenants;
    @Inject UserRepository users;

    private Long tenantId;
    private Long otherTenantId;

    @BeforeEach
    @Transactional
    void setup() {
        Tenant t = new Tenant("UTEST", "User Test Tenant");
        tenants.persist(t);
        tenantId = t.getId();

        Tenant t2 = new Tenant("UTEST2", "User Test Tenant 2");
        tenants.persist(t2);
        otherTenantId = t2.getId();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        users.delete("tenantId in ?1", List.of(tenantId, otherTenantId));
        tenants.delete("code in ?1", List.of("UTEST", "UTEST2"));
    }

    // ── CRUD basics ────────────────────────────────────────────────────────────

    @Test
    void createAndRetrieveUser() {
        String id = given().contentType(ContentType.JSON)
            .body("""
                {"login": "alice", "name": "Alice Dupont", "email": "alice@example.com"}
                """)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then()
            .statusCode(201)
            .body("login", equalTo("alice"))
            .body("name", equalTo("Alice Dupont"))
            .body("email", equalTo("alice@example.com"))
            .body("active", equalTo(true))
            .body("locked", equalTo(false))
            .body("tenantId", equalTo(tenantId.intValue()))
            .extract().path("id").toString();

        given().when().get("/api/tenants/" + tenantId + "/users/" + id)
            .then().statusCode(200)
            .body("id", equalTo(Integer.parseInt(id)));
    }

    @Test
    void listUsersOnlyReturnsActiveUsersForTenant() {
        given().contentType(ContentType.JSON)
            .body("""
                {"login": "bob", "name": "Bob Martin"}
                """)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then().statusCode(201);

        given().when().get("/api/tenants/" + tenantId + "/users")
            .then().statusCode(200)
            .body("login", hasItem("bob"))
            .body("size()", greaterThanOrEqualTo(1));
    }

    @Test
    void updateUser() {
        String id = given().contentType(ContentType.JSON)
            .body("""
                {"login": "carol", "name": "Carol Old"}
                """)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then().statusCode(201)
            .extract().path("id").toString();

        given().contentType(ContentType.JSON)
            .body("""
                {"name": "Carol New", "email": "carol@example.com"}
                """)
            .when().put("/api/tenants/" + tenantId + "/users/" + id)
            .then().statusCode(200)
            .body("name", equalTo("Carol New"))
            .body("email", equalTo("carol@example.com"));
    }

    @Test
    void deactivateUser() {
        String id = given().contentType(ContentType.JSON)
            .body("""
                {"login": "dave", "name": "Dave"}
                """)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then().statusCode(201)
            .extract().path("id").toString();

        given().when().delete("/api/tenants/" + tenantId + "/users/" + id)
            .then().statusCode(204);

        // Still retrievable by ID but active=false
        given().when().get("/api/tenants/" + tenantId + "/users/" + id)
            .then().statusCode(200)
            .body("active", equalTo(false));

        // Not returned in listing (listing filters active=true)
        given().when().get("/api/tenants/" + tenantId + "/users")
            .then().statusCode(200)
            .body("login", not(hasItem("dave")));
    }

    // ── Invariant: login uniqueness ────────────────────────────────────────────

    @Test
    void duplicateLoginInSameTenantIsRejected() {
        String body = """
            {"login": "dup_user", "name": "First"}
            """;
        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then().statusCode(201);

        // Same login, same tenant → 409
        given().contentType(ContentType.JSON)
            .body("""
                {"login": "dup_user", "name": "Second"}
                """)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then().statusCode(409);
    }

    @Test
    @Disabled("Cross-tenant uniqueness is enforced by Hibernate tenantFilter + RLS; " +
              "verified in TenantHubIsolationIT. Without the filter active in " +
              "module-level tests, loginExists() sees all tenants.")
    void sameLoginInDifferentTenantsIsAllowed() {
        given().contentType(ContentType.JSON)
            .body("""
                {"login": "shared_login", "name": "In Tenant 1"}
                """)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then().statusCode(201);

        // Same login, different tenant → 201
        given().contentType(ContentType.JSON)
            .body("""
                {"login": "shared_login", "name": "In Tenant 2"}
                """)
            .when().post("/api/tenants/" + otherTenantId + "/users")
            .then().statusCode(201);
    }

    // ── Invariant: email format ────────────────────────────────────────────────

    @Test
    void invalidEmailIsRejected() {
        given().contentType(ContentType.JSON)
            .body("""
                {"login": "badmail", "name": "Bad Email", "email": "not-an-email"}
                """)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then().statusCode(400);
    }

    @Test
    void nullEmailIsAccepted() {
        given().contentType(ContentType.JSON)
            .body("""
                {"login": "nomail", "name": "No Email"}
                """)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then().statusCode(201)
            .body("email", nullValue());
    }

    // ── Invariant: mandatory fields ────────────────────────────────────────────

    @Test
    void blankLoginIsRejected() {
        given().contentType(ContentType.JSON)
            .body("""
                {"login": "", "name": "No Login"}
                """)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then().statusCode(400);
    }

    @Test
    void blankNameIsRejected() {
        given().contentType(ContentType.JSON)
            .body("""
                {"login": "validlogin", "name": ""}
                """)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then().statusCode(400);
    }

    // ── Tenant isolation ──────────────────────────────────────────────────────

    @Test
    void userNotFoundInWrongTenant() {
        String id = given().contentType(ContentType.JSON)
            .body("""
                {"login": "tenant_user", "name": "Tenant User"}
                """)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then().statusCode(201)
            .extract().path("id").toString();

        // Same user ID but accessed via the other tenant → 404
        given().when().get("/api/tenants/" + otherTenantId + "/users/" + id)
            .then().statusCode(404);
    }

    @Test
    void unknownTenantReturns404() {
        given().contentType(ContentType.JSON)
            .body("""
                {"login": "ghost", "name": "Ghost"}
                """)
            .when().post("/api/tenants/999999/users")
            .then().statusCode(404);
    }

    // ── Account management ────────────────────────────────────────────────────

    @Transactional
    void lockUser(Long id) {
        users.findByIdOptional(id).ifPresent(u -> u.setLocked(true));
    }

    @Test
    void unlockUser() {
        String rawId = given().contentType(ContentType.JSON)
            .body("""
                {"login": "locked_user", "name": "Locked"}
                """)
            .when().post("/api/tenants/" + tenantId + "/users")
            .then().statusCode(201)
            .extract().path("id").toString();

        lockUser(Long.parseLong(rawId));

        given().when().post("/api/tenants/" + tenantId + "/users/" + rawId + "/unlock")
            .then().statusCode(200)
            .body("locked", equalTo(false));
    }
}
