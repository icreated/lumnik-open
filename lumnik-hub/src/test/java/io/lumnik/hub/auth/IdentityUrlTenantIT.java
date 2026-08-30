package io.lumnik.hub.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * The identity admin surface names a tenant in its own URL — {@code /api/tenants/{tenantId}/…} —
 * and never once compared it to the tenant in the caller's token.
 *
 * <p>Nothing leaked: RLS scopes every tenanted table to {@code app.current_tenant}, which the filter
 * sets from the JWT. What was wrong is subtler and worse to debug. {@code UserResource.listAll}
 * calls {@code requireTenant(tenantId)} — which only checks the tenant EXISTS — and then
 * {@code users.listAll()}, a query with <b>no tenant predicate at all</b>. So the path parameter is
 * not a filter, it is a fiction: an admin of tenant 2 asking for {@code /api/tenants/1/users} got
 * <b>their own</b> users, 200, under a URL naming someone else's tenant. A write went further and
 * failed as a database error rather than a refusal, because the RLS policy's WITH CHECK — not the
 * application — was the thing saying no.
 *
 * <p>It also let a caller probe which tenants exist: {@code requireTenant} 404s for an unknown id
 * and proceeds for a known one, so the two were distinguishable. The reconciliation now runs BEFORE
 * that lookup, so both answer alike.
 *
 * <p>This column of the authorization matrix could not be written before: every mint in
 * {@link NoBypassOidcProfile} hardcoded {@code tenant_id: 1}, so "the caller's tenant differs from
 * the URL's" was not a case the harness could express — which is why no test had ever pinned it.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class IdentityUrlTenantIT {

    /** Tenant 1 exists in the test fixture; the caller here is an admin of tenant 2. */
    @ParameterizedTest(name = "{0} under another tenant''s URL is refused")
    @CsvSource(delimiter = '|', value = {
        "GET  | /api/tenants/1/users",
        "GET  | /api/tenants/1/users/1",
        "GET  | /api/tenants/1/roles",
        "GET  | /api/tenants/1/orgs",
        "GET  | /api/tenants/1/orgs/tree",
        "GET  | /api/tenants/1/users/1/roles",
        "GET  | /api/tenants/1/roles/1/orgs",
        "DELETE | /api/tenants/1/users/1",
        "DELETE | /api/tenants/1/roles/1",
        "DELETE | /api/tenants/1/orgs/1",
    })
    void aForeignUrlTenantIsRefused(String method, String path) {
        given()
            .header("Authorization", "Bearer " + NoBypassOidcProfile.tokenForTenant(2L, "lm_admin"))
            .request(method.trim(), path.trim())
        .then()
            .statusCode(403);
    }

    @ParameterizedTest(name = "{0} under the caller''s own tenant is not refused by this rule")
    @CsvSource(delimiter = '|', value = {
        "GET | /api/tenants/1/users",
        "GET | /api/tenants/1/roles",
        "GET | /api/tenants/1/orgs",
    })
    void theCallersOwnTenantStillPassesTheGate(String method, String path) {
        given()
            .header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_admin"))
            .request(method.trim(), path.trim())
        .then()
            .statusCode(not403());
    }

    private static Matcher<Integer> not403() {
        return not(is(403));
    }

    /**
     * A tenant that does not exist and one that does must answer alike to a caller who owns
     * neither — otherwise the surface is a directory of which tenants exist.
     */
    @Test
    void anUnknownTenantIsIndistinguishableFromOneTheCallerDoesNotOwn() {
        String token = "Bearer " + NoBypassOidcProfile.tokenForTenant(2L, "lm_admin");
        int known = given().header("Authorization", token)
            .get("/api/tenants/1/users").then().extract().statusCode();
        int unknown = given().header("Authorization", token)
            .get("/api/tenants/999999/users").then().extract().statusCode();

        assertThat(known)
            .as("a tenant that exists and one that does not must not be told apart")
            .isEqualTo(unknown).isEqualTo(403);
    }
}
