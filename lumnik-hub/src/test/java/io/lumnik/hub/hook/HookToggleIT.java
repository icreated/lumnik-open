package io.lumnik.hub.hook;

import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class HookToggleIT {

    static final Long TENANT = 999930L;

    @BeforeEach void setTenant() { TenantContextFilter.CURRENT_TENANT.set(TENANT); }
    @AfterEach  void clear()      { TenantContextFilter.CURRENT_TENANT.remove(); }

    @Test
    void unknown_hook_returns_404() {
        given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("{\"enabled\": false}")
        .when().post("/api/platform/hooks/no-such-hook/toggle")
        .then().statusCode(404);
    }

    @Test
    void toggling_known_hook_returns_204() {
        String firstId = given().header("X-Tenant-ID", TENANT.toString())
        .when().get("/api/platform/hooks")
        .then().statusCode(200).extract().jsonPath().getString("hooks[0].id");

        if (firstId == null) return; // no hooks loaded — skip

        given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("{\"enabled\": false}")
        .when().post("/api/platform/hooks/" + firstId + "/toggle")
        .then().statusCode(204);

        // re-enable
        given().contentType("application/json")
            .header("X-Tenant-ID", TENANT.toString())
            .body("{\"enabled\": true}")
        .when().post("/api/platform/hooks/" + firstId + "/toggle")
        .then().statusCode(204);
    }
}
