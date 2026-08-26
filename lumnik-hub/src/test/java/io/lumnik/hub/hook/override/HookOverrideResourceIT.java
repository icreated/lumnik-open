package io.lumnik.hub.hook.override;

import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class HookOverrideResourceIT {

    static final Long TENANT = 999960L;
    static final String HOOK_ID = "io.lumnik.test.Override";

    @BeforeEach void setTenant() { TenantContextFilter.CURRENT_TENANT.set(TENANT); }

    @AfterEach void clear() {
        try {
            given().header("X-Tenant-ID", TENANT.toString())
                   .delete("/api/platform/hooks/overrides/" + HOOK_ID + "?tenantId=" + TENANT);
        } finally {
            TenantContextFilter.CURRENT_TENANT.remove();
        }
    }

    @Test
    void upsert_then_get_returns_script() {
        given()
            .contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("{\"hookId\": \"" + HOOK_ID + "\", \"tenantId\": " + TENANT + ", \"script\": \"val x = 42\"}")
        .when().post("/api/platform/hooks/overrides")
        .then().statusCode(204);

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/platform/hooks/overrides/" + HOOK_ID + "?tenantId=" + TENANT)
        .then().statusCode(200)
               .body("script", equalTo("val x = 42"))
               .body("hookId", equalTo(HOOK_ID));
    }

    @Test
    void delete_removes_override() {
        given()
            .contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("{\"hookId\": \"" + HOOK_ID + "\", \"tenantId\": " + TENANT + ", \"script\": \"val x = 1\"}")
        .when().post("/api/platform/hooks/overrides")
        .then().statusCode(204);

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().delete("/api/platform/hooks/overrides/" + HOOK_ID + "?tenantId=" + TENANT)
        .then().statusCode(204);

        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/platform/hooks/overrides/" + HOOK_ID + "?tenantId=" + TENANT)
        .then().statusCode(404);
    }

    @Test
    void list_requires_tenantId() {
        given()
            .header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/platform/hooks/overrides")
        .then().statusCode(400);
    }
}
