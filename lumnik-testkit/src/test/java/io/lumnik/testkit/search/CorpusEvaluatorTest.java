package io.lumnik.testkit.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.lumnik.extraction.spi.search.IndexedSearchHit;
import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.testkit.search.corpus.CorpusEntity;
import io.lumnik.testkit.search.corpus.CorpusQuery;
import io.lumnik.testkit.search.corpus.CorpusRow;
import io.lumnik.testkit.search.corpus.Judgment;
import io.lumnik.testkit.search.corpus.SearchCorpus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CorpusEvaluatorTest {

    /**
     * Returns hits in a fixed, deliberately imperfect order (grade-1 hit before grade-3 hit) so the
     * computed nDCG is verifiably not 1.0 — proving the evaluator actually wires ranking order into
     * the metric rather than always reporting a perfect score.
     */
    private static IndexedSearchPort fixedOrderProvider(String... rowIdsInOrder) {
        return new IndexedSearchPort() {
            @Override
            public String name() {
                return "fixed-order-stub";
            }

            @Override
            public ProviderStatus status() {
                return ProviderStatus.available();
            }

            @Override
            public IndexedSearchResult search(IndexedSearchQuery query) {
                List<IndexedSearchHit> hits = List.of(rowIdsInOrder).stream()
                        .map(id -> new IndexedSearchHit("customer", "customer", Map.of("id", id), 1.0,
                                Map.of(), Map.of()))
                        .toList();
                return new IndexedSearchResult(hits, Map.of(), null, null);
            }
        };
    }

    @Test
    void evaluatesAQueryUsingTheRetrievalOrderTheProviderReturned() {
        SearchCorpus corpus = new SearchCorpus(1, 704, List.of(
                new CorpusEntity("customer", List.of(
                        new CorpusRow("row-a", 1, Map.of("name", "Alpha")),
                        new CorpusRow("row-b", 1, Map.of("name", "Beta"))))),
                List.of(new CorpusQuery("q1", "en", "alpha beta", 1, List.of("customer"), List.of(),
                        List.of(new Judgment("row-a", 3), new Judgment("row-b", 1)))));

        // Provider deliberately returns row-b (grade 1) before row-a (grade 3).
        List<CorpusEvaluator.QueryResult> results =
                CorpusEvaluator.evaluate(corpus, fixedOrderProvider("row-b", "row-a"));

        assertThat(results).hasSize(1);
        CorpusEvaluator.QueryResult result = results.get(0);
        assertThat(result.queryId()).isEqualTo("q1");
        // DCG = 1/log2(2) + 7/log2(3); IDCG = 7/log2(2) + 1/log2(3) -> nDCG ~= 0.70975
        assertThat(result.ndcgAt10()).isCloseTo(0.70975, within(1e-4));
        // recall@10: grade>=2 relevant retrieved (row-a) / total grade>=2 (row-a only) = 1.0
        assertThat(result.recallAt10()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void failsFastWhenAHitDoesNotCarryAnIdKey() {
        SearchCorpus corpus = new SearchCorpus(1, 704, List.of(
                new CorpusEntity("customer", List.of(
                        new CorpusRow("row-a", 1, Map.of("name", "Alpha"))))),
                List.of(new CorpusQuery("q1", "en", "alpha", 1, List.of("customer"), List.of(),
                        List.of(new Judgment("row-a", 3)))));
        IndexedSearchPort providerWithNoIdKey = new IndexedSearchPort() {
            @Override
            public String name() {
                return "broken-provider";
            }

            @Override
            public ProviderStatus status() {
                return ProviderStatus.available();
            }

            @Override
            public IndexedSearchResult search(IndexedSearchQuery query) {
                IndexedSearchHit hit = new IndexedSearchHit("customer", "customer",
                        Map.of("code", "row-a"), 1.0, Map.of(), Map.of());
                return new IndexedSearchResult(List.of(hit), Map.of(), null, null);
            }
        };

        assertThatThrownBy(() -> CorpusEvaluator.evaluate(corpus, providerWithNoIdKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken-provider")
                .hasMessageContaining("id");
    }

    @Test
    void failsFastWhenTheProviderReturnsTheSameRowIdTwice() {
        SearchCorpus corpus = new SearchCorpus(1, 704, List.of(
                new CorpusEntity("customer", List.of(
                        new CorpusRow("row-a", 1, Map.of("name", "Alpha"))))),
                List.of(new CorpusQuery("q1", "en", "alpha", 1, List.of("customer"), List.of(),
                        List.of(new Judgment("row-a", 3)))));

        assertThatThrownBy(() -> CorpusEvaluator.evaluate(corpus, fixedOrderProvider("row-a", "row-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixed-order-stub")
                .hasMessageContaining("row-a")
                .hasMessageContaining("duplicate");
    }
}
