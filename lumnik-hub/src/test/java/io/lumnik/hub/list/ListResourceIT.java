package io.lumnik.hub.list;

import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ListResourceIT {

    /** Lazy tenant — created once on first @BeforeEach (server is up by then). */
    static final AtomicLong TENANT = new AtomicLong(-1);

    @BeforeEach
    void setup() {
        if (TENANT.get() < 0) {
            Long id = given().contentType("application/json")
                .body("{\"code\":\"LIST-IT-TENANT\",\"name\":\"List IT Tenant\"}")
            .when().post("/api/tenants")
            .then()
                .extract().jsonPath().getLong("id");

            // If id is 0/null (409 conflict), look it up
            if (id == null || id <= 0) {
                id = given()
                .when().get("/api/tenants")
                .then().statusCode(200)
                    .extract().jsonPath()
                    .getLong("find { it.code == 'LIST-IT-TENANT' }.id");
            }
            TENANT.set(id);
        }

        TenantContextFilter.CURRENT_TENANT.set(TENANT.get());
    }

    @AfterEach
    void clear() { TenantContextFilter.CURRENT_TENANT.remove(); }

    @Test
    void create_definition_then_add_value_then_update_and_remove() {
        long tid = TENANT.get();

        Integer listId = given().contentType("application/json")
            .header("X-Tenant-ID", Long.toString(tid))
            .body("{\"code\":\"partner_segment\",\"name\":\"Segmentation partenaires\"}")
        .when().post("/api/tenants/" + tid + "/lists")
        .then().statusCode(201)
            .body("code", equalTo("partner_segment"))
            .body("systemOwned", equalTo(false))
            .extract().path("id");

        Integer valueId = given().contentType("application/json")
            .header("X-Tenant-ID", Long.toString(tid))
            .body("{\"value\":\"VIP\",\"name\":\"Client VIP\",\"color\":\"#FF0000\"}")
        .when().post("/api/tenants/" + tid + "/lists/" + listId + "/values")
        .then().statusCode(201)
            .body("value", equalTo("VIP"))
            .extract().path("id");

        given().contentType("application/json")
            .header("X-Tenant-ID", Long.toString(tid))
            .body("{\"name\":\"VIP Client\"}")
        .when().put("/api/tenants/" + tid + "/lists/" + listId + "/values/" + valueId)
            .then().statusCode(200).body("name", equalTo("VIP Client"));

        given()
            .header("X-Tenant-ID", Long.toString(tid))
        .when().delete("/api/tenants/" + tid + "/lists/" + listId + "/values/" + valueId)
            .then().statusCode(204);

        given()
            .header("X-Tenant-ID", Long.toString(tid))
        .when().get("/api/tenants/" + tid + "/lists/" + listId + "/values")
            .then().statusCode(200)
            .body("find { it.id == " + valueId + " }.active", equalTo(false));
    }

    @Test
    void list_without_tenant_context_is_forbidden() {
        long tid = TENANT.get();

        // No X-Tenant-ID header: the request-thread TenantContextFilter never sets a tenant,
        // so requireTenant() must fail closed rather than silently let an arbitrary tid through.
        given()
        .when().get("/api/tenants/" + tid + "/lists")
            .then().statusCode(403);
    }

    @Test
    void creating_duplicate_code_returns_409() {
        long tid = TENANT.get();

        given().contentType("application/json")
            .header("X-Tenant-ID", Long.toString(tid))
            .body("{\"code\":\"dup_code\",\"name\":\"first\"}")
        .when().post("/api/tenants/" + tid + "/lists").then().statusCode(201);

        given().contentType("application/json")
            .header("X-Tenant-ID", Long.toString(tid))
            .body("{\"code\":\"dup_code\",\"name\":\"second\"}")
        .when().post("/api/tenants/" + tid + "/lists").then().statusCode(409);
    }
}
