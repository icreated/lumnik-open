package io.lumnik.hub.security;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1994: the hub sends its own security headers, so every packaging carries them. The PWA keeps its tokens in
 * sessionStorage: a script injection would be a token theft with no second line, so /app/ answers with a CSP that
 * runs only the hub's own scripts and cannot be framed.
 */
@QuarkusTest
class SecurityHeadersIT {

    @Test
    void everyAnswerForbidsSniffingAndKeepsItsReferrerHome() {
        for (Response r : new Response[] {given().get("/app/"), given().get("/api/bff/v1/me")}) {
            assertThat(r.header("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(r.header("Referrer-Policy")).isEqualTo("same-origin");
        }
    }

    @Test
    void thePwaRunsOnlyTheHubsScriptsAndNoOtherSiteFramesIt() {
        String csp = given().get("/app/").then().statusCode(200).extract().header("Content-Security-Policy");
        assertThat(csp).isNotNull()
            .contains("default-src 'self'", "script-src 'self'", "frame-ancestors 'self'", "object-src 'none'",
                "connect-src 'self' http://localhost:" + PwaContentSecurityPolicy.KEYCLOAK_PORT + ";")
            .doesNotContain("*", "unsafe-inline", "unsafe-eval");
        assertThat(given().get("/api/bff/v1/me").header("Content-Security-Policy")).as("the API serves no page").isNull();
        assertThat(given().get("/app/src/config.mjs").header("Content-Security-Policy")).isEqualTo(csp);
    }
}
