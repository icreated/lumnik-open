package io.lumnik.hub.rag;

import io.lumnik.llm.spi.EmbeddingPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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
}
