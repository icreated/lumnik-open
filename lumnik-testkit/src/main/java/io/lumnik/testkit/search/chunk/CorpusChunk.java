package io.lumnik.testkit.search.chunk;

import java.util.List;

/** One retrievable chunk. {@code type} is {@code connector} (an ingested row) or {@code workflow}. */
public record CorpusChunk(String id, long tenantId, String corpus, List<String> tags, String type, String text) {

    public CorpusChunk {
        tags = tags == null ? List.of() : tags;
    }

    /**
     * The one visibility rule, mirroring {@code RetrievalService.searchSql}: same tenant; a corpus filter only when the
     * query names one; a tag-overlap filter only when the query carries scope tags (empty = unscoped).
     */
    public boolean visibleTo(CorpusChunkQuery q) {
        if (tenantId != q.tenantId()) {
            return false;
        }
        if (q.corpus() != null && !q.corpus().isBlank() && !q.corpus().equals(corpus)) {
            return false;
        }
        return q.scopeTags().isEmpty() || tags.stream().anyMatch(q.scopeTags()::contains);
    }
}
