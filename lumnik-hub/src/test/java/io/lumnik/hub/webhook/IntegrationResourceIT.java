package io.lumnik.hub.webhook;

import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class IntegrationResourceIT {

    static final Long TENANT = 999921L;

    @BeforeEach
    void setTenant() {
        TenantContextFilter.CURRENT_TENANT.set(TENANT);
    }

    @AfterEach
    void clearTenant() {
        TenantContextFilter.CURRENT_TENANT.remove();
    }

    @Test
    void create_list_update_delete_roundtrip() {
        Integer id = given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("""
                  {"name":"make-repairs","targetUrl":"https://hook.make.com/abc",
                   "platform":"make","authMode":"BEARER",
                   "secret":"sk-test-123",
                   "eventTypes":["repair.completed"]}""")
        .when().post("/api/tenants/" + TENANT + "/integrations")
        .then().statusCode(201)
            .body("name", equalTo("make-repairs"))
            .body("platform", equalTo("make"))
            .body("authMode", equalTo("BEARER"))
            .body("hasSecret", equalTo(true))
            .extract().path("id");

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/tenants/" + TENANT + "/integrations")
            .then().statusCode(200)
            .body("name", hasItem("make-repairs"));

        given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("""
                  {"enabled": false}""")
        .when().put("/api/tenants/" + TENANT + "/integrations/" + id)
            .then().statusCode(200)
            .body("enabled", equalTo(false));

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().delete("/api/tenants/" + TENANT + "/integrations/" + id)
            .then().statusCode(204);
    }

    @Test
    void deliveries_with_zero_limit_answers_instead_of_throwing() {
        Integer id = given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("""
                  {"name":"zero-limit-probe","targetUrl":"https://hook.make.com/zero",
                   "platform":"make","authMode":"BEARER",
                   "secret":"sk-test-123",
                   "eventTypes":["repair.completed"]}""")
        .when().post("/api/tenants/" + TENANT + "/integrations")
        .then().statusCode(201)
            .extract().path("id");

        // limit=0 is a nonsense request; it must clamp, not surface page(0,0)'s exception as a 500.
        given().header("X-Tenant-ID", TENANT.toString())
            .when().get("/api/tenants/" + TENANT + "/integrations/" + id + "/deliveries?limit=0")
            .then().statusCode(200);
        given().header("X-Tenant-ID", TENANT.toString())
            .when().get("/api/tenants/" + TENANT + "/integrations/" + id + "/deliveries?limit=0&status=failed")
            .then().statusCode(200);

        given().header("X-Tenant-ID", TENANT.toString())
            .when().delete("/api/tenants/" + TENANT + "/integrations/" + id)
            .then().statusCode(204);
    }

    @Test
    void list_without_tenant_context_is_forbidden() {
        // No X-Tenant-ID header: the request-thread TenantContextFilter never sets a tenant,
        // so requireTenant() must fail closed rather than silently let an arbitrary tid through.
        given()
        .when().get("/api/tenants/" + TENANT + "/integrations")
            .then().statusCode(403);
    }

    @Test
    void test_event_with_unknown_type_returns_400() {
        Integer id = given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("""
                  {"name":"test-1","targetUrl":"https://example.com/hook",
                   "platform":"generic","authMode":"NONE",
                   "eventTypes":["org.created"]}""")
        .when().post("/api/tenants/" + TENANT + "/integrations")
            .then().statusCode(201).extract().path("id");

        given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("""
                  {"eventType":"does.not.exist"}""")
        .when().post("/api/tenants/" + TENANT + "/integrations/" + id + "/test")
            .then().statusCode(400);
    }
}
