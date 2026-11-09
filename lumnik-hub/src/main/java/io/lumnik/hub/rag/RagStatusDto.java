package io.lumnik.hub.rag;

import java.time.Instant;

/**
 * {@code chunks} and {@code indexedAt} describe the platform indexing of this JVM (what the last boot
 * indexed). {@code storedChunks} is what the store holds for the caller's tenant — null when the build
 * has no RAG module — and {@code switchedOff} says the module is installed but the AI is off, so a
 * {@code chunks} of 0 there is not an empty corpus.
 */
public record RagStatusDto(
    boolean indexed,
    int chunks,
    Instant indexedAt,
    String provider,
    String embeddingModel,
    boolean switchedOff,
    Long storedChunks
) {}
