package io.lumnik.identity.tenant;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class TenantResourceTest {

    @Inject
    TenantRepository tenants;

    @AfterEach
    @Transactional
    void cleanup() {
        tenants.delete("code in ?1", java.util.List.of("TEST01", "DUPTEST"));
    }

    @Test
    void createAndRetrieveTenant() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("""
                {"code": "TEST01", "name": "Test Tenant", "description": "Integration test"}
                """)
            .when().post("/api/tenants")
            .then()
            .statusCode(201)
            .body("code", equalTo("TEST01"))
            .body("name", equalTo("Test Tenant"))
            .body("active", equalTo(true))
            .extract().path("id").toString();

        given()
            .when().get("/api/tenants/" + id)
            .then()
            .statusCode(200)
            .body("id", equalTo(Integer.parseInt(id)))
            .body("code", equalTo("TEST01"));
    }

    @Test
    void duplicateCodeIsRejected() {
        String body = """
            {"code": "DUPTEST", "name": "Dup Tenant"}
            """;
        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/tenants").then().statusCode(201);

        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/tenants").then().statusCode(409);
    }

    @Test
    void missingCodeReturns400() {
        given().contentType(ContentType.JSON)
            .body("""
                {"name": "No Code Tenant"}
                """)
            .when().post("/api/tenants")
            .then().statusCode(400);
    }

    @Test
    void notFoundReturns404() {
        given().when().get("/api/tenants/999999")
            .then().statusCode(404);
    }
}
