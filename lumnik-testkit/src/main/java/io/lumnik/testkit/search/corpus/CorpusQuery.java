package io.lumnik.testkit.search.corpus;

import java.util.List;

public record CorpusQuery(
        String id,
        String language,
        String text,
        long tenantId,
        List<String> allowedEntities,
        List<String> tags,
        List<Judgment> judgments) {

    public CorpusQuery {
        allowedEntities = allowedEntities == null ? List.of() : allowedEntities;
        tags = tags == null ? List.of() : tags;
        judgments = judgments == null ? List.of() : judgments;
    }
}
