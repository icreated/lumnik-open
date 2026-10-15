package io.lumnik.testkit.search;

import io.lumnik.extraction.spi.search.IndexedSearchHit;
import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.testkit.search.corpus.CorpusQuery;
import io.lumnik.testkit.search.corpus.Judgment;
import io.lumnik.testkit.search.corpus.SearchCorpus;
import io.lumnik.testkit.search.metrics.Ndcg;
import io.lumnik.testkit.search.metrics.Recall;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs every corpus query through an {@link IndexedSearchPort} and scores the result against its
 * judgments. Convention: a hit is matched back to a corpus row by {@code hit.key().get("id")}
 * equaling the judged row id — any provider under evaluation must project that id under key "id".
 */
public final class CorpusEvaluator {

    private static final int K = 10;

    public record QueryResult(String queryId, double ndcgAt10, double recallAt10, double latencyMillis) {
    }

    private CorpusEvaluator() {
    }

    public static List<QueryResult> evaluate(SearchCorpus corpus, IndexedSearchPort provider) {
        return corpus.queries().stream().map(query -> evaluate(query, provider)).toList();
    }

    private static QueryResult evaluate(CorpusQuery query, IndexedSearchPort provider) {
        Map<String, Integer> gradeByRowId = query.judgments().stream()
                .collect(Collectors.toMap(Judgment::hit, Judgment::relevance));

        IndexedSearchQuery indexedQuery = new IndexedSearchQuery(query.tenantId(),
                new HashSet<>(query.allowedEntities()), query.text(), Map.of(), K, null);

        long start = System.nanoTime();
        IndexedSearchResult result = provider.search(indexedQuery);
        double latencyMillis = (System.nanoTime() - start) / 1_000_000.0;

        List<String> rowIds = result.hits().stream().map(hit -> rowId(hit, provider)).toList();
        Set<String> seenRowIds = new HashSet<>();
        for (String id : rowIds) {
            if (!seenRowIds.add(id)) {
                throw new IllegalStateException("provider " + provider.name()
                        + " returned duplicate hits for row id \"" + id
                        + "\"; a provider under evaluation must not return the same row more than once");
            }
        }
        List<Integer> retrievedGrades = rowIds.stream()
                .map(id -> gradeByRowId.getOrDefault(id, 0))
                .toList();
        List<Integer> allJudgedGrades = query.judgments().stream().map(Judgment::relevance).toList();
        int totalRelevant = (int) query.judgments().stream()
                .filter(j -> j.relevance() >= Recall.RELEVANT_THRESHOLD).count();

        double ndcg = Ndcg.at(K, retrievedGrades, allJudgedGrades);
        double recall = Recall.at(K, retrievedGrades, totalRelevant);

        return new QueryResult(query.id(), ndcg, recall, latencyMillis);
    }

    private static String rowId(IndexedSearchHit hit, IndexedSearchPort provider) {
        String id = hit.key().get("id");
        if (id == null) {
            throw new IllegalStateException("provider " + provider.name()
                    + " returned a hit with no \"id\" key (key=" + hit.key()
                    + "); a provider under evaluation must project the corpus row id under key \"id\"");
        }
        return id;
    }
}
