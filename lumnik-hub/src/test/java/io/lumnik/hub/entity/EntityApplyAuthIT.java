package io.lumnik.hub.entity;

import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Verifies the unauthenticated contract for POST /api/entities/apply when dev-bypass is off:
 * - no JWT → 401 (unauthenticated — TenantContextFilter aborts on missing tenant_id claim)
 *
 * The wrong-role → 403 path is covered by the OTK wrong-role-apply variant.
 * Symmetric to RoleResourceNoBypassIT for the entity surface.
 */
@QuarkusTest
@TestProfile(EntityApplyAuthIT.NoBypassProfile.class)
class EntityApplyAuthIT {

    public static class NoBypassProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("lumnik.security.dev-bypass", "false");
        }
    }

    static final String VALID_MANIFEST = """
            kind: Entity
            name: Customer
            match: [id]
            sources:
              - table: t_cust_src
                map:
                  ROW_ID: id
            """;

    @Test
    void apply_noJwt_returns401() {
        given().config(YamlBody.CONFIG)
                .contentType("application/yaml")
                .body(VALID_MANIFEST)
                .post("/api/entities/apply")
                .then().statusCode(401);
    }
}
