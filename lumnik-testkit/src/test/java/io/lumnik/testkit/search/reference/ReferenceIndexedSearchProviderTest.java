package io.lumnik.testkit.search.reference;

import static org.assertj.core.api.Assertions.assertThat;

import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.testkit.search.corpus.CorpusEntity;
import io.lumnik.testkit.search.corpus.CorpusRow;
import io.lumnik.testkit.search.corpus.SearchCorpus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReferenceIndexedSearchProviderTest {

    @Test
    void findsARowByExactCaseInsensitiveTokenMatch() {
        SearchCorpus corpus = new SearchCorpus(1, 704, List.of(
                new CorpusEntity("customer", List.of(
                        new CorpusRow("row-001", 1, Map.of("name", "Alpha"))))),
                List.of());
        ReferenceIndexedSearchProvider provider = new ReferenceIndexedSearchProvider(corpus);

        IndexedSearchResult result = provider.search(
                new IndexedSearchQuery(1, Set.of("customer"), "alpha", Map.of(), 10, null));

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).key()).containsEntry("id", "row-001");
    }

    @Test
    void excludesRowsSharingNoTokenWithTheQueryText() {
        SearchCorpus corpus = new SearchCorpus(1, 704, List.of(
                new CorpusEntity("customer", List.of(
                        new CorpusRow("row-001", 1, Map.of("name", "Alpha"))))),
                List.of());
        ReferenceIndexedSearchProvider provider = new ReferenceIndexedSearchProvider(corpus);

        IndexedSearchResult result = provider.search(
                new IndexedSearchQuery(1, Set.of("customer"), "zzz-no-match", Map.of(), 10, null));

        assertThat(result.hits()).isEmpty();
    }

    @Test
    void excludesRowsBelongingToAnotherTenant() {
        SearchCorpus corpus = new SearchCorpus(1, 704, List.of(
                new CorpusEntity("customer", List.of(
                        new CorpusRow("row-001", 2, Map.of("name", "Alpha"))))),
                List.of());
        ReferenceIndexedSearchProvider provider = new ReferenceIndexedSearchProvider(corpus);

        IndexedSearchResult result = provider.search(
                new IndexedSearchQuery(1, Set.of("customer"), "alpha", Map.of(), 10, null));

        assertThat(result.hits()).isEmpty();
    }

    @Test
    void excludesRowsWhoseEntityIsNotInAllowedEntities() {
        SearchCorpus corpus = new SearchCorpus(1, 704, List.of(
                new CorpusEntity("order", List.of(
                        new CorpusRow("row-001", 1, Map.of("name", "Alpha"))))),
                List.of());
        ReferenceIndexedSearchProvider provider = new ReferenceIndexedSearchProvider(corpus);

        IndexedSearchResult result = provider.search(
                new IndexedSearchQuery(1, Set.of("customer"), "alpha", Map.of(), 10, null));

        assertThat(result.hits()).isEmpty();
    }

    @Test
    void ranksRowsWithMoreSharedTokensFirst() {
        SearchCorpus corpus = new SearchCorpus(1, 704, List.of(
                new CorpusEntity("customer", List.of(
                        new CorpusRow("weak-match", 1, Map.of("name", "Alpha Industries")),
                        new CorpusRow("strong-match", 1, Map.of("name", "Alpha Beta"))))),
                List.of());
        ReferenceIndexedSearchProvider provider = new ReferenceIndexedSearchProvider(corpus);

        IndexedSearchResult result = provider.search(
                new IndexedSearchQuery(1, Set.of("customer"), "Alpha Beta", Map.of(), 10, null));

        assertThat(result.hits()).extracting(hit -> hit.key().get("id"))
                .containsExactly("strong-match", "weak-match");
    }

    @Test
    void doesNotMatchATransliteratedNameItWasNeverToldAbout() {
        // Deliberately naive: no accent folding, no phonetics. Müller/Mueller is a documented
        // known limitation, not a bug — the harness's baseline captures this, it never asserts it away.
        SearchCorpus corpus = new SearchCorpus(1, 704, List.of(
                new CorpusEntity("customer", List.of(
                        new CorpusRow("row-001", 1, Map.of("name", "Müller GmbH"))))),
                List.of());
        ReferenceIndexedSearchProvider provider = new ReferenceIndexedSearchProvider(corpus);

        IndexedSearchResult result = provider.search(
                new IndexedSearchQuery(1, Set.of("customer"), "Mueller", Map.of(), 10, null));

        assertThat(result.hits()).isEmpty();
    }
}
