package io.lumnik.hub.rag;

import io.lumnik.extraction.spi.ExtractedChunk;
import io.lumnik.hub.rag.chunk.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkBuilderTest {

    // ── ManifestChunkBuilder ────────────────────────────────────────────────

    @Test
    void manifest_overviewChunk_containsModuleId() {
        var builder = new ManifestChunkBuilder();
        List<ExtractedChunk> chunks = builder.build(
            "lumnik-orders", "Orders", "orders",
            List.of("orders.read", "orders.manage"),
            List.of("orders.created", "orders.status_changed", "orders.completed")
        );

        ExtractedChunk overview = chunks.stream()
            .filter(c -> "overview".equals(c.type()))
            .findFirst().orElseThrow();

        assertThat(overview.module()).isEqualTo("lumnik-orders");
        assertThat(overview.content()).contains("lumnik-orders");
        assertThat(overview.content()).contains("orders.read");
        assertThat(overview.content()).contains("orders.created");
    }

    @Test
    void manifest_oneEventChunkPerPublishedEvent() {
        var builder = new ManifestChunkBuilder();
        List<ExtractedChunk> chunks = builder.build(
            "lumnik-orders", "Orders", "orders",
            List.of(),
            List.of("orders.created", "orders.completed")
        );

        long eventChunks = chunks.stream().filter(c -> "event".equals(c.type())).count();
        assertThat(eventChunks).isEqualTo(2);

        ExtractedChunk eventChunk = chunks.stream()
            .filter(c -> "event".equals(c.type()) && "orders.completed".equals(c.ref()))
            .findFirst().orElseThrow();
        assertThat(eventChunk.content()).contains("orders.completed");
        assertThat(eventChunk.metadata()).containsEntry("moduleId", "lumnik-orders");
    }

    @Test
    void manifest_noEvents_onlyOverviewChunk() {
        var builder = new ManifestChunkBuilder();
        List<ExtractedChunk> chunks = builder.build(
            "lumnik-rag", "RAG", "rag", List.of("rag.search"), List.of()
        );
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).type()).isEqualTo("overview");
    }

    // ── OpenApiChunkBuilder ─────────────────────────────────────────────────

    @Test
    void openapi_oneChunkPerOperation() {
        var builder = new OpenApiChunkBuilder();
        String yaml = """
            openapi: "3.0.0"
            info:
              title: Test
              version: "1"
            paths:
              /api/bff/v1/repairs:
                get:
                  summary: List repairs
                  tags: [repair]
                  operationId: listRepairs
              /api/bff/v1/repairs/{id}:
                get:
                  summary: Get repair by id
                  tags: [repair]
                  operationId: getRepair
            """;

        List<ExtractedChunk> chunks = builder.buildFromYaml(yaml);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allMatch(c -> "operation".equals(c.type()));
        assertThat(chunks).anyMatch(c -> c.content().contains("List repairs"));
    }

    @Test
    void openapi_chunkContainsMethodAndPath() {
        var builder = new OpenApiChunkBuilder();
        String yaml = """
            openapi: "3.0.0"
            info:
              title: Test
              version: "1"
            paths:
              /api/identity/orgs:
                post:
                  summary: Create org
                  tags: [identity]
                  operationId: createOrg
            """;

        List<ExtractedChunk> chunks = builder.buildFromYaml(yaml);
        assertThat(chunks).hasSize(1);
        ExtractedChunk chunk = chunks.get(0);
        assertThat(chunk.content()).contains("POST");
        assertThat(chunk.content()).contains("/api/identity/orgs");
        assertThat(chunk.metadata()).containsEntry("method", "POST");
        assertThat(chunk.metadata()).containsEntry("path", "/api/identity/orgs");
    }

    // ── HookChunkBuilder ────────────────────────────────────────────────────

    @Test
    void hook_chunkContainsHookIdAndLifecycle() {
        var builder = new HookChunkBuilder();
        List<ExtractedChunk> chunks = builder.build(
            "orders-completed-notify", "lumnik-orders",
            "AFTER_STATUS_CHANGE", "OrderStatus.DONE",
            List.of("event: OrderCompleted", "ctx: HookContext")
        );

        assertThat(chunks).hasSize(1);
        ExtractedChunk chunk = chunks.get(0);
        assertThat(chunk.type()).isEqualTo("definition");
        assertThat(chunk.ref()).isEqualTo("orders-completed-notify");
        assertThat(chunk.content()).contains("orders-completed-notify");
        assertThat(chunk.content()).contains("AFTER_STATUS_CHANGE");
        assertThat(chunk.content()).contains("OrderStatus.DONE");
        assertThat(chunk.metadata()).containsEntry("hookId", "orders-completed-notify");
        assertThat(chunk.metadata()).containsEntry("lifecycle", "AFTER_STATUS_CHANGE");
    }
}
