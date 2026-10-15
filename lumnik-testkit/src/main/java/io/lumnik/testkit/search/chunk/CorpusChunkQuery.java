package io.lumnik.testkit.search.chunk;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * A corpus query. Scored classes carry {@code judgments} (chunk id → grade 0..3). The isolation class carries
 * {@code mustRankFirst} (a control: permitted, dominant, must come first) and/or {@code mustBeAbsent} (a leak case:
 * forbidden chunks that would dominate if nothing filtered them).
 */
public record CorpusChunkQuery(
        String id,
        @JsonProperty("class") QueryClass queryClass,
        String language,
        long tenantId,
        String corpus,
        List<String> scopeTags,
        String text,
        Map<String, Integer> judgments,
        String mustRankFirst,
        List<String> mustBeAbsent) {

    public CorpusChunkQuery {
        scopeTags = scopeTags == null ? List.of() : scopeTags;
        judgments = judgments == null ? Map.of() : judgments;
        mustBeAbsent = mustBeAbsent == null ? List.of() : mustBeAbsent;
    }
}
