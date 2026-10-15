package io.lumnik.testkit.search.corpus;

import java.util.List;

public record SearchCorpus(int schemaVersion, long seed, List<CorpusEntity> entities, List<CorpusQuery> queries) {

    public SearchCorpus {
        entities = entities == null ? List.of() : entities;
        queries = queries == null ? List.of() : queries;
    }
}
