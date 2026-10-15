package io.lumnik.testkit.search.chunk;

import java.util.List;
import java.util.Map;

/**
 * A retrieval strategy under evaluation in the #675 bench. Bench-private on purpose: it is NOT a production SPI, and
 * nothing in {@code lumnik-extraction-spi} mirrors it. Implementations live in test scope of the module whose
 * dependencies they need and are never CDI beans.
 */
public interface ChunkRetrievalCandidate extends AutoCloseable {

    record CandidateQuery(long tenantId, String corpus, List<String> scopeTags, String text, float[] vector, int topK) {
    }

    String name();

    /** Prepares the backend for this corpus (index, schema). May be a no-op when the lane already seeded the data. */
    void load(ChunkCorpus corpus, Map<String, float[]> vectors) throws Exception;

    /** Corpus chunk ids, best first, never the same id twice. */
    List<String> retrieve(CandidateQuery query) throws Exception;

    @Override
    default void close() throws Exception {
    }
}
