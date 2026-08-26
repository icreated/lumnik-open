package io.lumnik.hub.security.roles;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RoleResourceIT {

    @Test
    void getRoles_asAdmin_returns200WithCatalog() {
        given()
          .when().get("/api/platform/roles")
          .then().statusCode(200)
          .body("roles", hasSize(3))
          .body("roles.find { it.id == 'lm_superadmin' }.wildcard", equalTo(true))
          .body("roles.find { it.id == 'lm_superadmin' }.permissions", hasSize(0))
          .body("roles.find { it.id == 'lm_admin' }.permissions.size()", greaterThan(0));
    }

    @Test
    void getRoles_permissionDtoIncludesModuleAndLabel() {
        given()
          .when().get("/api/platform/roles")
          .then().statusCode(200)
          .body("roles.find { it.id == 'lm_admin' }.permissions[0].id", notNullValue())
          .body("roles.find { it.id == 'lm_admin' }.permissions[0].module", notNullValue())
          .body("roles.find { it.id == 'lm_admin' }.permissions[0].module", not(equalTo("unknown")));
    }

    @Test
    void getRoles_includesCacheControlHeader() {
        given()
          .when().get("/api/platform/roles")
          .then().statusCode(200)
          .header("Cache-Control", containsString("max-age=60"));
    }

    @Test
    void getRoles_wildcardRoleHasEmptyPermissions() {
        given()
          .when().get("/api/platform/roles")
          .then().statusCode(200)
          .body("roles.find { it.id == 'lm_superadmin' }.permissions", hasSize(0))
          .body("roles.find { it.id == 'lm_superadmin' }.wildcard", equalTo(true));
    }
}
