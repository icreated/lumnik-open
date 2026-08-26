package io.lumnik.hub.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;

/**
 * Gate proof for the five identity admin resources (users/roles/orgs/user-roles/role-org-access)
 * and {@code CalloutResource}: a plain {@code lm_user} JWT must be refused (403) on a write verb
 * of each identity resource; an {@code lm_admin} JWT must not be. {@code CalloutResource} gates
 * on {@code lm_user} instead — matching the rest of the app surface — so there the roles invert:
 * a JWT without {@code lm_user} must be refused, and one with it must not be.
 *
 * <p>Before this gate existed, RLS still blocked cross-tenant access, but any plain {@code
 * lm_user} inside their own tenant could create/delete colleagues' accounts and mutate the
 * role/org structure — the privilege problem this IT proves closed.
 *
 * <p>See {@link NoBypassOidcProfile}'s javadoc for why this needs a real, signed OIDC JWT rather
 * than {@code @TestSecurity}/{@code @JwtSecurity} on this OIDC-only app.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class IdentityAdminAuthIT {

    @Test
    void user_delete_lmUser_is403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_user"))
            .when().delete("/api/tenants/1/users/1")
            .then().statusCode(403);
    }

    @Test
    void user_delete_lmAdmin_isNot403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_admin"))
            .when().delete("/api/tenants/1/users/999999")
            .then().statusCode(not(403));
    }

    @Test
    void role_delete_lmUser_is403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_user"))
            .when().delete("/api/tenants/1/roles/1")
            .then().statusCode(403);
    }

    @Test
    void role_delete_lmAdmin_isNot403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_admin"))
            .when().delete("/api/tenants/1/roles/999999")
            .then().statusCode(not(403));
    }

    @Test
    void org_delete_lmUser_is403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_user"))
            .when().delete("/api/tenants/1/orgs/1")
            .then().statusCode(403);
    }

    @Test
    void org_delete_lmAdmin_isNot403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_admin"))
            .when().delete("/api/tenants/1/orgs/999999")
            .then().statusCode(not(403));
    }

    @Test
    void userRole_remove_lmUser_is403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_user"))
            .when().delete("/api/tenants/1/users/1/roles/1")
            .then().statusCode(403);
    }

    @Test
    void userRole_remove_lmAdmin_isNot403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_admin"))
            .when().delete("/api/tenants/1/users/999999/roles/999999")
            .then().statusCode(not(403));
    }

    @Test
    void roleOrgAccess_revoke_lmUser_is403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_user"))
            .when().delete("/api/tenants/1/roles/1/orgs/1")
            .then().statusCode(403);
    }

    @Test
    void roleOrgAccess_revoke_lmAdmin_isNot403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_admin"))
            .when().delete("/api/tenants/1/roles/999999/orgs/999999")
            .then().statusCode(not(403));
    }

    @Test
    void callout_post_withoutLmUser_is403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_integrator"))
            .contentType("application/json").body("{}")
            .when().post("/api/callouts/hub/NoSuchEntity/field")
            .then().statusCode(403);
    }

    @Test
    void callout_post_withLmUser_isNot403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_user"))
            .contentType("application/json").body("{}")
            .when().post("/api/callouts/hub/NoSuchEntity/field")
            .then().statusCode(not(403));
    }
}
