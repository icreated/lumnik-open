package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.llm.spi.EmbeddingPort;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The refusal is true whether the model is absent (Open) or switched off (lumnik.ai.enabled=false). */
class EmbeddingServiceTest {

    private static EmbeddingService withoutAModel() {
        var svc = new EmbeddingService();
        @SuppressWarnings("unchecked")
        Instance<EmbeddingPort> none = mock(Instance.class);
        when(none.isUnsatisfied()).thenReturn(true);
        svc.embeddingPorts = none;
        return svc;
    }

    @Test
    void embed_without_a_model_says_so_without_blaming_the_build() {
        assertThatThrownBy(() -> withoutAModel().embed("x"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("no embedding model is available — RAG indexing needs one");
    }

    @Test
    void embedAll_without_a_model_says_so_without_blaming_the_build() {
        assertThatThrownBy(() -> withoutAModel().embedAll(List.of("x")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("no embedding model is available — RAG indexing needs one");
    }
}
