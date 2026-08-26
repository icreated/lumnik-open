package io.lumnik.hub.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;

/**
 * Gate proof for {@code TenantResource} ({@code /api/tenants}): an {@code lm_user} JWT must be
 * refused (403); an {@code lm_superadmin} JWT must not be.
 *
 * <p>See {@link NoBypassOidcProfile}'s javadoc for why this needs a real, signed OIDC JWT rather
 * than {@code @TestSecurity}/{@code @JwtSecurity} on this OIDC-only app.
 */
@QuarkusTest
@TestProfile(NoBypassOidcProfile.class)
class TenantAdminAuthIT {

    @Test
    void lmUser_list_is403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_user"))
            .when().get("/api/tenants")
            .then().statusCode(403);
    }

    /**
     * Pins the shape of a role refusal on the wire, because five documentation pages tell readers
     * to tell three different 403s apart by their body: this one is EMPTY, a métier-scope refusal
     * carries {@code {"error":"scope not granted"}}, and {@code /api/platform/*} answers the plain
     * text {@code missing role lm_integrator} from {@code PlatformApiAuthFilter}.
     *
     * <p>{@code PermissionInterceptor} throws {@code ForbiddenException("Insufficient
     * permissions")}, which reads as though the message reaches the client — it does not: no
     * {@code ExceptionMapper} handles it, so JAX-RS answers 403 with no entity and the message
     * stays in the exception and the log. A reviewer read the throw site and concluded the
     * opposite, which is exactly why this is measured here rather than inferred.
     */
    @Test
    void roleRefusal_carriesAnEmptyBody() {
        String body = given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_user"))
            .when().get("/api/tenants")
            .then().statusCode(403)
            .extract().asString();
        org.assertj.core.api.Assertions.assertThat(body)
            .as("a role refusal must stay body-less — the docs distinguish it from the scope and "
                + "platform refusals by exactly that")
            .isEmpty();
    }

    @Test
    void lmUser_delete_is403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_user"))
            .when().delete("/api/tenants/1")
            .then().statusCode(403);
    }

    @Test
    void lmSuperadmin_list_isNot403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_superadmin"))
            .when().get("/api/tenants")
            .then().statusCode(not(403));
    }

    @Test
    void lmSuperadmin_delete_isNot403() {
        given().header("Authorization", "Bearer " + NoBypassOidcProfile.token("lm_superadmin"))
            .when().delete("/api/tenants/999999")
            .then().statusCode(not(403));
    }
}
