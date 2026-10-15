package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.llm.spi.EmbeddingPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Thin façade over EmbeddingPort. Isolates the connector stack from the LLM SPI surface.
 * Throws IllegalStateException when no embedding model is available: none on the classpath
 * (e.g. ELK-only deployment without lumnik-llm-langchain) or switched off (lumnik.ai.enabled=false).
 */
@ApplicationScoped
public class EmbeddingService {

    @Inject Instance<EmbeddingPort> embeddingPorts;

    public float[] embed(String text) {
        if (embeddingPorts.isUnsatisfied()) {
            throw new IllegalStateException(
                "no embedding model is available — RAG indexing needs one");
        }
        return embeddingPorts.get().embed(text);
    }

    /** Embed N texts in one provider call; returns N vectors in the same order. */
    public List<float[]> embedAll(List<String> texts) {
        if (embeddingPorts.isUnsatisfied()) {
            throw new IllegalStateException(
                "no embedding model is available — RAG indexing needs one");
        }
        return embeddingPorts.get().embedAll(texts);
    }
}
