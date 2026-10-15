package io.lumnik.hub.rag;

import io.lumnik.extraction.spi.ExtractedChunk;
import io.lumnik.extraction.spi.ExtractionSink;
import io.lumnik.hub.hook.HookRegistry;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.lumnik.hub.rag.chunk.HookChunkBuilder;
import io.lumnik.hub.rag.chunk.ManifestChunkBuilder;
import io.lumnik.hub.rag.chunk.OpenApiChunkBuilder;
import io.lumnik.llm.spi.EmbeddingPort;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * The status endpoint must describe the system truthfully — through the live SPI port, never
 * through constants. The old hardcoded "ollama"/"nomic-embed-text" kept reporting a local
 * provider even after a rebinding: exactly the false reassurance a security reviewer asking
 * "does my data leave?" must not get (triangle audit, P2 on #286).
 */
class PlatformIndexerStatusTest {

    private PlatformIndexer withPort(EmbeddingPort port) {
        PlatformIndexer ix = new PlatformIndexer();
        @SuppressWarnings("unchecked")
        Instance<EmbeddingPort> inst = mock(Instance.class);
        when(inst.isUnsatisfied()).thenReturn(port == null);
        if (port != null) when(inst.get()).thenReturn(port);
        ix.embeddingPorts = inst;
        return ix;
    }

    @Test
    void status_reports_the_live_port_not_constants() {
        EmbeddingPort port = mock(EmbeddingPort.class);
        when(port.name()).thenReturn("test-provider");
        when(port.model()).thenReturn("test-model");

        RagStatusDto dto = withPort(port).status();

        assertThat(dto.provider()).isEqualTo("test-provider");
        assertThat(dto.embeddingModel()).isEqualTo("test-model");
    }

    @Test
    void status_names_absence_honestly_when_no_port_ships() {
        // Open edition: no embedding port. "none" is the honest answer — never a
        // constant claiming a provider that is not there.
        RagStatusDto dto = withPort(null).status();

        assertThat(dto.provider()).isEqualTo("none");
        assertThat(dto.embeddingModel()).isEqualTo("none");
    }

    // ── reindex() says what happened (D15) ────────────────────────────────────

    private static ExtractionSink sink(boolean works) {
        ExtractionSink sink = mock(ExtractionSink.class);
        when(sink.name()).thenReturn("rag");
        if (!works) doThrow(new IllegalStateException("down")).when(sink).index(anyLong(), any(), any(), any());
        return sink;
    }

    /** A real PlatformIndexer over mocked collaborators; {@code port} null = no embedding model. */
    private PlatformIndexer indexer(EmbeddingPort port, ExtractionSink... sinks) {
        PlatformIndexer ix = withPort(port);
        ix.manifestRegistry = mock(ModuleManifestRegistry.class);
        ix.hookRegistry = mock(HookRegistry.class);
        ix.manifestChunkBuilder = mock(ManifestChunkBuilder.class);
        ix.openApiChunkBuilder = mock(OpenApiChunkBuilder.class);
        ix.hookChunkBuilder = mock(HookChunkBuilder.class);
        when(ix.openApiChunkBuilder.buildFromClasspath()).thenReturn(List.of(
            new ExtractedChunk("openapi", "hub", "a", "A", Map.of()),
            new ExtractedChunk("openapi", "hub", "b", "B", Map.of())));
        @SuppressWarnings("unchecked")
        Instance<ExtractionSink> all = mock(Instance.class);
        when(all.iterator()).thenAnswer(i -> List.of(sinks).iterator());
        ix.sinks = all;
        return ix;
    }

    @Test
    void without_an_embedding_model_nothing_is_indexed_and_the_status_says_so() {
        ExtractionSink sink = sink(true);
        PlatformIndexer ix = indexer(null, sink);

        ix.reindex();

        verify(sink, never()).index(anyLong(), any(), any(), any());
        RagStatusDto dto = ix.status();
        assertThat(dto.indexed()).isFalse();
        assertThat(dto.chunks()).isZero();
        assertThat(dto.provider()).isEqualTo("none");
    }

    @Test
    void with_a_model_but_no_sink_the_status_does_not_claim_an_index() {
        PlatformIndexer ix = indexer(mock(EmbeddingPort.class));

        ix.reindex();

        assertThat(ix.status().indexed()).isFalse();
        assertThat(ix.status().chunks()).isZero();
    }

    @Test
    void a_sink_that_failed_is_not_an_index_either() {
        PlatformIndexer ix = indexer(mock(EmbeddingPort.class), sink(false));

        ix.reindex();

        assertThat(ix.status().indexed()).isFalse();
    }

    @Test
    void a_sink_that_took_the_chunks_makes_the_status_true() {
        ExtractionSink sink = sink(true);
        PlatformIndexer ix = indexer(mock(EmbeddingPort.class), sink);

        ix.reindex();

        verify(sink).index(anyLong(), any(), any(), any());
        RagStatusDto dto = ix.status();
        assertThat(dto.indexed()).isTrue();
        assertThat(dto.chunks()).isEqualTo(2);
        assertThat(dto.indexedAt()).isNotNull();
    }
}
