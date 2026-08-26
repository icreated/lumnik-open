package io.lumnik.hub.security.roles;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(RoleResourceNoBypassIT.NoBypassProfile.class)
class RoleResourceNoBypassIT {

    public static class NoBypassProfile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("lumnik.security.dev-bypass", "false");
        }
    }

    @Test
    void getRoles_anonymous_returns401or403() {
        given()
          .when().get("/api/platform/roles")
          .then().statusCode(anyOf(equalTo(401), equalTo(403)));
    }

    @Test
    void getRoles_noJwt_returns401() {
        // No JWT present, dev-bypass off: PlatformApiAuthFilter (priority
        // AUTHENTICATION+100) runs BEFORE the @RequiresPermission interceptor and,
        // finding no principal, aborts with 401 + WWW-Authenticate: Bearer. 401 is
        // the correct contract for "unauthenticated" (403 = authenticated-but-forbidden,
        // which would require a valid token lacking the role).
        given()
          .when().get("/api/platform/roles")
          .then().statusCode(401);
    }
}
