package io.lumnik.hub.rag;

import io.lumnik.llm.spi.EmbeddingPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class RagResourceIT {

    // Required to satisfy EmbeddingPort build-time bean resolution
    @InjectMock
    EmbeddingPort embeddingPort;

    @InjectMock
    PlatformIndexer platformIndexer;

    @Test
    void getStatus_returns200WithIndexInfo() {
        when(platformIndexer.status())
            .thenReturn(new RagStatusDto(true, 178, Instant.now(), "ollama", "nomic-embed-text"));

        given()
            .header("X-Lumnik-Roles", "lm_admin")
        .when()
            .get("/api/platform/rag/status")
        .then()
            .statusCode(200)
            .body("indexed", is(true))
            .body("chunks", is(178))
            .body("provider", is("ollama"));
    }

    @Test
    void postReindex_returns202() {
        given()
            .header("X-Lumnik-Roles", "lm_admin")
        .when()
            .post("/api/platform/rag/reindex")
        .then()
            .statusCode(202)
            .body("status", is("reindex_started"));
    }

    /**
     * 403 tests require dev-bypass=false so PermissionInterceptor actually checks roles.
     * In dev-bypass mode, all roles are always granted.
     */
    @QuarkusTest
    @TestProfile(WithoutRoleTest.NoBypassProfile.class)
    static class WithoutRoleTest {

        public static class NoBypassProfile implements QuarkusTestProfile {
            @Override
            public Map<String, String> getConfigOverrides() {
                return Map.of(
                    "lumnik.security.dev-bypass", "false",
                    "lumnik.rag.index-on-startup", "false"
                );
            }
        }

        @Test
        void adminEndpoints_withoutAdminRole_return403() {
            given().when().get("/api/platform/rag/status").then().statusCode(403);
            given().when().post("/api/platform/rag/reindex").then().statusCode(403);
        }
    }
}
