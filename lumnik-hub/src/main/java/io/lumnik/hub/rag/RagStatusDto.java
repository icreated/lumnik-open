package io.lumnik.hub.rag;

import java.time.Instant;

public record RagStatusDto(
    boolean indexed,
    int chunks,
    Instant indexedAt,
    String provider,
    String embeddingModel
) {}
