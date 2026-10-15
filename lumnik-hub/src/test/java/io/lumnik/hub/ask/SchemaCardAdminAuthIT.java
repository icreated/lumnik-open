package io.lumnik.hub.ask;

import io.lumnik.hub.auth.DevBypassOffProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;


import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * With dev-bypass=false an anonymous request to {@code /api/platform/rag/*} is aborted <b>401 by
 * {@link io.lumnik.hub.api.PlatformApiAuthFilter}</b>, which sees no principal. Ordering is what
 * decides this: that filter is {@code @Priority(AUTHENTICATION + 100)} = 1100, ahead of
 * {@code TenantContextFilter} at {@code @Priority(AUTHORIZATION)} = 2000, so neither the tenant
 * claim nor the resource's role check is ever reached. The historical 403 expectation predated real
 * OIDC. A role-denied 403 needs an authenticated token without the role; that is asserted for the
 * sibling surface by {@link io.lumnik.hub.rag.RagPlatformAuthIT}.
 *
 * <p>The {@code WWW-Authenticate: Bearer} assertion below is not decoration — it is what makes the
 * attribution checkable rather than asserted. The two filters refuse differently:
 * PlatformApiAuthFilter sends that challenge with a plain-text body, TenantContextFilter sends
 * {@code {"error": …}} as JSON with no challenge. The header names which one answered.
 * (Carried-over javadoc credited TenantContextFilter here; caught in review on #333.)
 *
 * <p><b>Why this is a file and not a nested class.</b> It lived as a {@code static} nested class
 * inside {@code RagSchemaResourceClosedIT} and failsafe's {@code **}{@code /*IT.java} discovery
 * never found it — the suite reported that class with 5 tests and this one had NEVER RUN. Unlike its
 * counterpart in {@code RagResourceIT} it was correct all along; it simply proved nothing.
 */
@QuarkusTest
@TestProfile(DevBypassOffProfile.class)
class SchemaCardAdminAuthIT {

    @ParameterizedTest(name = "unauthenticated {0} {1} → 401 from PlatformApiAuthFilter")
    @CsvSource({"GET,/api/platform/rag/schema?scope=atelierC",
                "POST,/api/platform/rag/rediscover?scope=atelierC"})
    void withoutAuth_isRefusedWith401(String method, String path) {
        given().header("X-Tenant-ID", "8300").contentType(ContentType.JSON)
            .when().request(method, path)
            .then().statusCode(401)
            .header("WWW-Authenticate", containsString("Bearer"));
    }
}
