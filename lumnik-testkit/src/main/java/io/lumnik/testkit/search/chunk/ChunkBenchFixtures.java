package io.lumnik.testkit.search.chunk;

import java.util.List;

/** Names and loads the committed #675 corpus + frozen vectors, so every lane reads the same files. */
public final class ChunkBenchFixtures {

    public static final List<String> CORPUS_RESOURCES = List.of(
        ChunkFixture.DIR + "corpus-core.yaml",
        ChunkFixture.DIR + "corpus-distractors.yaml");

    private ChunkBenchFixtures() {
    }

    public static ChunkCorpusLoader.Loaded loadCorpus() {
        return ChunkCorpusLoader.loadResources(CORPUS_RESOURCES.toArray(String[]::new));
    }

    public static ChunkFixture.Loaded loadVectors(ChunkCorpus corpus) {
        return ChunkFixture.loadResources(corpus);
    }
}
