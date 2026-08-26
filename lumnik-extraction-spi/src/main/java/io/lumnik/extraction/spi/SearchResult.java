package io.lumnik.extraction.spi;

import java.util.Map;

public record SearchResult(
    String content,
    double score,
    Map<String, String> metadata
) {
    public SearchResult {
        if (metadata == null) metadata = Map.of();
    }
}
