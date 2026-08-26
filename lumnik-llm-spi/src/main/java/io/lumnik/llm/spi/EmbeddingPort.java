package io.lumnik.llm.spi;

import java.util.List;

public interface EmbeddingPort {

    /** Unique port identifier ("ollama-nomic", "openai-ada-002", ...). */
    String name();

    /** The effective embedding model identifier, for truthful status reporting; "unknown"
     *  when the implementation cannot say. */
    default String model() { return "unknown"; }

    /** Embed N texts; returns N vectors in the same order. Safe for concurrent calls. */
    List<float[]> embedAll(List<String> texts);

    /** Convenience: embed a single text. */
    default float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    /** Vector dimension produced (e.g. 768 for nomic-embed-text). */
    int dimension();
}
