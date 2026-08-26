package io.lumnik.hub.rag;

import io.lumnik.llm.spi.EmbeddingPort;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

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
}
