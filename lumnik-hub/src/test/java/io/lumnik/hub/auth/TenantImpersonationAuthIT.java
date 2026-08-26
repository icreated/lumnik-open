package io.lumnik.hub.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate proof for {@code TenantContextFilter}'s handling of the {@code acting_as_tenant} claim:
 * the impersonation ENDPOINT ({@code ImpersonationResource}) already requires {@code
 * lm_superadmin}, but the filter that actually switches the RLS tenant on every subsequent
 * request did not — any bearer whose IdP ever minted the claim could cross tenants. A plain
 * {@code lm_user} JWT carrying the claim must be refused (403); the same claim on an {@code
 * lm_superadmin} JWT must be honoured, and honoured for real — this asserts the impersonated
 * tenant's own id/code come back from {@code GET /api/bff/v1/me}, not merely a non-403 status.
 *
 * <p>See {@link NoBypassOidcProfile}'s javadoc for why this needs a real, signed OIDC JWT rather
 * than {@code @TestSecurity}/{@code @JwtSecurity} on this OIDC-only app.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class TenantImpersonationAuthIT {

    /** Create a second tenant, distinct from the seeded tenant 1 that every token()'s tenant_id
     *  claims, via the superadmin-gated /api/tenants (Task 1) — so the positive-case assertion
     *  below can prove a REAL cross-tenant switch: the response's tenant id/code must match this
     *  tenant, not just "the request wasn't refused". Created per-test (not in a shared
     *  {@code @BeforeAll}): the QuarkusTest/RestAssured wiring isn't ready yet when
     *  {@code @BeforeAll} runs, which turned an earlier version of this call into a spurious 401
     *  unrelated to the gate under test. A fresh code per call avoids collisions if the test runs
     *  more than once against the same database. */
    private static long createOtherTenant(String code) {
        return given()
            .header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_superadmin"))
            .contentType("application/json")
            .body("{\"code\":\"" + code + "\",\"name\":\"Impersonation IT tenant\"}")
            .when().post("/api/tenants")
            .then().statusCode(201)
            .extract().jsonPath().getLong("id");
    }

    @Test
    void lmUser_actingAsTenant_is403() {
        long otherTenantId = createOtherTenant("itimp" + System.nanoTime());

        given().header("Authorization", "Bearer " +
                NoBypassOidcProfile.token(Set.of("lm_user"), otherTenantId))
            .when().get("/api/bff/v1/me")
            .then().statusCode(403);
    }

    @Test
    void lmSuperadmin_actingAsTenant_switchesTenant() {
        String code = "itimp" + System.nanoTime();
        long otherTenantId = createOtherTenant(code);

        // lm_user is also required here to clear BffResource's own @RequiresPermission("lm_user")
        // gate — a separate, already-correct check this test does not exercise.
        var response = given().header("Authorization", "Bearer " +
                NoBypassOidcProfile.token(Set.of("lm_superadmin", "lm_user"), otherTenantId))
            .when().get("/api/bff/v1/me")
            .then().statusCode(200)
            .extract().jsonPath();

        assertThat(response.getLong("tenant.id")).as("impersonated tenant id took effect")
            .isEqualTo(otherTenantId);
        assertThat(response.getString("tenant.code")).as("impersonated tenant code took effect")
            .isEqualTo(code);
    }
}
