package io.lumnik.testkit.search.chunk;

import java.util.List;

public record ChunkCorpus(int schemaVersion, long seed, List<CorpusChunk> chunks, List<CorpusChunkQuery> queries) {

    public ChunkCorpus {
        chunks = chunks == null ? List.of() : chunks;
        queries = queries == null ? List.of() : queries;
    }
}
