package io.lumnik.hub.admin;

import io.lumnik.common.security.CurrentRolesHolder;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;

@QuarkusTest
class TenantListResourceIT {

    @BeforeEach
    void setup() {
        CurrentRolesHolder.set(Set.of("lm_superadmin"));
        // Ensure at least one tenant exists in the DB
        given().contentType("application/json")
            .body("""
                {"code":"ADMIN-IT-TENANT","name":"Admin IT Tenant"}""")
        .when().post("/api/tenants")
        .then(); // ignore 201 or 409-conflict if already exists
    }

    @AfterEach
    void clear() {
        CurrentRolesHolder.clear();
    }

    @Test
    void lists_at_least_one_tenant() {
        given()
        .when().get("/api/platform/admin/tenants")
        .then().statusCode(200)
            .body("size()", greaterThan(0));
    }
}
