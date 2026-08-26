package io.lumnik.hub.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Verifies {@code PlatformApiAuthFilter}'s authentication/authorization distinction on
 * {@code /api/platform/*} when dev-bypass is OFF: anonymous → 401 (no principal, with a Bearer challenge),
 * authenticated-without-role → 403 (a principal is present but lacks {@code lm_integrator}). The filter
 * inspects the JAX-RS {@code SecurityContext} ({@code getUserPrincipal} / {@code isUserInRole}), which is
 * exactly what {@code @TestSecurity} drives — so these two outcomes are tested faithfully here.
 *
 * <p><b>The integrator → 200 path is deliberately NOT asserted here.</b> Reaching the endpoint also requires
 * {@code TenantContextFilter} to resolve a {@code tenant_id} from the request's JWT, and
 * {@code @TestSecurity}/{@code @JwtSecurity} does NOT populate the injected {@link org.eclipse.microprofile.jwt.JsonWebToken}
 * on this OIDC-based app (there is no {@code quarkus-smallrye-jwt}) — the token comes up empty, so the
 * filter correctly aborts 401 on the missing claim. That full chain (a real Bearer → tenant resolution →
 * endpoint) is covered end-to-end with a real Keycloak token by the JIT-provisioning and SCIM e2e tests,
 * and is proven live in the self-host deploy. {@code TenantContextFilter}'s own claim handling is unit-
 * tested in {@code TenantContextFilterJwtTest}.
 */
@QuarkusTest
@TestProfile(PlatformAuthIT.RealAuthProfile.class)
class PlatformAuthIT {

    public static class RealAuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "lumnik.security.dev-bypass", "false",
                "quarkus.oidc.roles.role-claim-path", "groups",
                "quarkus.keycloak.devservices.enabled", "false");
        }
    }

    @Test
    void anonymous_is_401_with_bearer_challenge() {
        // No authenticated principal → 401 with a WWW-Authenticate: Bearer challenge (RFC 7235).
        given().when().get("/api/platform/connectors")
            .then().statusCode(401)
            .header("WWW-Authenticate", org.hamcrest.Matchers.containsString("Bearer"));
    }

    @Test
    @TestSecurity(user = "u1")
    void authenticated_without_integrator_role_is_403() {
        // Principal present but no lm_integrator role → 403 (the filter aborts before tenant resolution).
        given().when().get("/api/platform/connectors").then().statusCode(403);
    }
}
