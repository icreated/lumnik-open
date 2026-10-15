package io.lumnik.hub.rag;

import io.lumnik.hub.auth.DevBypassOffProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;


import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The two refusals {@code PlatformApiAuthFilter} distinguishes on {@code /api/platform/rag/*} when
 * dev-bypass is off — and they are DIFFERENT answers, which is why they need different requests.
 *
 * <p>Anonymous → <b>401</b> with a {@code WWW-Authenticate: Bearer} challenge (RFC 7235 §3.1): there
 * is no principal to authorize. Authenticated but without {@code lm_integrator} → <b>403</b>.
 *
 * <p><b>Why this is a file and not a nested class.</b> It lived as a {@code static} nested class
 * inside {@code RagResourceIT}, where failsafe's {@code **}{@code /*IT.java} discovery never found
 * it: the suite reported {@code RagResourceIT} with 2 tests, and this one had NEVER RUN. Being dead
 * is also what let it keep a wrong expectation — it asserted 403 for an ANONYMOUS request, which the
 * filter answers 401. Its sibling {@code SchemaCardAdminAuthIT} had already hit and documented
 * that same 403 → 401 correction; a test that cannot fail cannot carry the lesson.
 *
 * @see io.lumnik.hub.auth.PlatformAuthIT the same distinction, made correctly all along
 */
@QuarkusTest
@TestProfile(DevBypassOffProfile.class)
class RagPlatformAuthIT {

    @ParameterizedTest(name = "anonymous {0} {1} → 401 + Bearer challenge")
    @CsvSource({"GET,/api/platform/rag/status", "POST,/api/platform/rag/reindex"})
    void anonymous_isRefusedWith401AndABearerChallenge(String method, String path) {
        given().when().request(method, path)
            .then().statusCode(401)
            .header("WWW-Authenticate", containsString("Bearer"));
    }

    @ParameterizedTest(name = "authenticated without lm_integrator: {0} {1} → 403")
    @CsvSource({"GET,/api/platform/rag/status", "POST,/api/platform/rag/reindex"})
    @TestSecurity(user = "u1")
    void authenticated_withoutIntegratorRole_isRefusedWith403(String method, String path) {
        given().when().request(method, path).then().statusCode(403);
    }
}
