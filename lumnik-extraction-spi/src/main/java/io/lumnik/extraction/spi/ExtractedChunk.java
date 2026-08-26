package io.lumnik.extraction.spi;

import java.util.Map;

public record ExtractedChunk(
    String type,
    String module,
    String ref,
    String content,
    Map<String, String> metadata
) {
    public ExtractedChunk {
        if (metadata == null) metadata = Map.of();
    }
}
