package io.lumnik.testkit.search.corpus;

import java.util.List;

public record CorpusEntity(String name, List<CorpusRow> rows) {

    public CorpusEntity {
        rows = rows == null ? List.of() : rows;
    }
}
