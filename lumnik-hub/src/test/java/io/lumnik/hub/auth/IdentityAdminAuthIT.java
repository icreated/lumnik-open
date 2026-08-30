package io.lumnik.hub.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;

/**
 * The authorization matrix for the identity admin surface, as a matrix.
 *
 * <p>Written as twelve one-off methods it covered two role columns and nobody could see which two.
 * Folded, the shape is a grid — endpoint × caller — and the columns that were never filled become
 * missing rows rather than an absence nobody names. {@code lm_integrator} and the anonymous caller
 * were both untested across the whole surface; they are rows here now.
 *
 * <p>The privilege this proves closed: before the gate existed, RLS still blocked cross-tenant
 * access, but any plain {@code lm_user} inside their own tenant could create or delete colleagues'
 * accounts and mutate the role/org structure.
 *
 * <p>Two details the table has to carry, because they are load-bearing and easy to lose in a fold:
 * <ul>
 *   <li>the ids differ per row on purpose. A refused caller is refused before any lookup, so id 1
 *       is safe; a caller who gets THROUGH must land on 999999, an id that does not exist, so the
 *       row proves the gate opened without deleting anything real.
 *   <li>{@code CalloutResource} gates on {@code lm_user} like the rest of the app surface, not on
 *       {@code lm_admin}, so its roles invert. Keeping it in the same table is what makes that
 *       inversion visible instead of surprising.
 * </ul>
 *
 * <p>See {@link NoBypassOidcProfile}'s javadoc for why this needs a real, signed OIDC JWT rather
 * than {@code @TestSecurity}/{@code @JwtSecurity} on this OIDC-only app.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class IdentityAdminAuthIT {

    /** No role at all — the request carries no Authorization header. */
    private static final String ANONYMOUS = "-";

    @ParameterizedTest(name = "{2} calling {0} {1} -> {3}")
    @CsvSource(delimiter = '|', value = {
        // ── users ────────────────────────────────────────────────────────────
        "DELETE | /api/tenants/1/users/1              | lm_user        | 403",
        "DELETE | /api/tenants/1/users/1              | lm_integrator  | 403",
        "DELETE | /api/tenants/1/users/1              | -              | 401",
        "DELETE | /api/tenants/1/users/999999         | lm_admin       | 404",

        // ── roles ────────────────────────────────────────────────────────────
        "DELETE | /api/tenants/1/roles/1              | lm_user        | 403",
        "DELETE | /api/tenants/1/roles/1              | lm_integrator  | 403",
        "DELETE | /api/tenants/1/roles/1              | -              | 401",
        "DELETE | /api/tenants/1/roles/999999         | lm_admin       | 404",

        // ── orgs ─────────────────────────────────────────────────────────────
        "DELETE | /api/tenants/1/orgs/1               | lm_user        | 403",
        "DELETE | /api/tenants/1/orgs/1               | lm_integrator  | 403",
        "DELETE | /api/tenants/1/orgs/1               | -              | 401",
        "DELETE | /api/tenants/1/orgs/999999          | lm_admin       | 404",

        // ── user → role assignment ───────────────────────────────────────────
        "DELETE | /api/tenants/1/users/1/roles/1           | lm_user       | 403",
        "DELETE | /api/tenants/1/users/1/roles/1           | lm_integrator | 403",
        "DELETE | /api/tenants/1/users/1/roles/1           | -             | 401",
        "DELETE | /api/tenants/1/users/999999/roles/999999 | lm_admin      | 404",

        // ── role → org access ────────────────────────────────────────────────
        "DELETE | /api/tenants/1/roles/1/orgs/1            | lm_user       | 403",
        "DELETE | /api/tenants/1/roles/1/orgs/1            | lm_integrator | 403",
        "DELETE | /api/tenants/1/roles/1/orgs/1            | -             | 401",
        "DELETE | /api/tenants/1/roles/999999/orgs/999999  | lm_admin      | 404",
    })
    void theIdentityAdminSurfaceAdmitsOnlyAnAdmin(String method, String path,
                                                  String role, String expected) {
        assertGate(request(role).request(method.trim(), path.trim()), expected);
    }

    /**
     * The same grid for {@code CalloutResource}, whose gate is {@code lm_user} — the app surface's
     * role, not the identity surface's. An admin token without {@code lm_user} is refused here,
     * which reads as a contradiction until the two tables sit side by side.
     */
    @ParameterizedTest(name = "{0} calling the callout surface -> {1}")
    @CsvSource(delimiter = '|', value = {
        "lm_user        | 404",
        "lm_integrator  | 403",
        "lm_admin       | 403",
        "-              | 401",
    })
    void theCalloutSurfaceAdmitsOnlyAnAppUser(String role, String expected) {
        assertGate(request(role)
            .contentType("application/json").body("{}")
            .post("/api/callouts/hub/NoSuchEntity/field"), expected);
    }

    private static RequestSpecification request(String role) {
        String r = role.trim();
        return ANONYMOUS.equals(r)
            ? given()
            : given().header("Authorization", "Bearer " + NoBypassOidcProfile.token(r));
    }

    /**
     * Every row names its status, deliberately. The methods this replaced asserted
     * {@code not(403)} for the admin rows, which cannot tell "the gate opened" from "refused for
     * some other reason" — a token rejected with 401 would have satisfied it just as well. 404 says
     * the handler ran and found nothing, which is what the row is actually claiming.
     */
    private static void assertGate(Response response, String expected) {
        response.then().statusCode(Integer.parseInt(expected.trim()));
    }
}
