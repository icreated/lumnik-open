package io.lumnik.testkit.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.testkit.search.corpus.CorpusLoader;
import io.lumnik.testkit.search.corpus.CorpusQuery;
import io.lumnik.testkit.search.corpus.SearchCorpus;
import io.lumnik.testkit.search.reference.ReferenceIndexedSearchProvider;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * The reference provider's naive token-overlap scoring should succeed on the corpus's easy,
 * unambiguous cases and is allowed — expected, even — to fail on the deliberately hard
 * accent/transliteration cases. Only the aggregate baseline in
 * {@link ReferenceProviderEndToEndTest} is regression-gated; this test documents which individual
 * cases are known-good and known-hard so a change in either direction is visible and explained
 * rather than silently absorbed into an aggregate number.
 */
class ReferenceProviderKnownCasesTest {

    private static final SearchCorpus CORPUS =
            CorpusLoader.loadResource("search-corpus/v1/direct-search.yaml");
    private static final ReferenceIndexedSearchProvider PROVIDER = new ReferenceIndexedSearchProvider(CORPUS);

    private static CorpusQuery queryById(SearchCorpus corpus, String id) {
        return corpus.queries().stream().filter(q -> q.id().equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException(id));
    }

    private static IndexedSearchResult run(ReferenceIndexedSearchProvider provider, CorpusQuery query) {
        return provider.search(new IndexedSearchQuery(query.tenantId(), new HashSet<>(query.allowedEntities()),
                query.text(), Map.of(), 10, null));
    }

    @Test
    void succeedsOnAnExactIdentifierMatch() {
        IndexedSearchResult result = run(PROVIDER, queryById(CORPUS, "exact-code-de-0042"));

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).key()).containsEntry("id", "customer-de-001");
    }

    @Test
    void succeedsOnAFullTokenCompanyFormMatch() {
        IndexedSearchResult result = run(PROVIDER, queryById(CORPUS, "de-abbreviation-gmbh"));

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).key()).containsEntry("id", "customer-de-002");
    }

    @Test
    void succeedsOnASimplePrefixMatch() {
        IndexedSearchResult result = run(PROVIDER, queryById(CORPUS, "en-prefix-acme"));

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).key()).containsEntry("id", "customer-en-001");
    }

    @Test
    void knownHardCaseTransliterationFindsNothingAndDoesNotCrash() {
        IndexedSearchResult result = run(PROVIDER, queryById(CORPUS, "de-transliteration-mueller"));

        assertThat(result.hits()).isEmpty();
    }

    @Test
    void knownHardCasePlainTransliterationFindsNothingAndDoesNotCrash() {
        IndexedSearchResult result = run(PROVIDER, queryById(CORPUS, "de-transliteration-muller"));

        assertThat(result.hits()).isEmpty();
    }

    @Test
    void knownHardCaseAccentFindsNothingAndDoesNotCrash() {
        IndexedSearchResult result = run(PROVIDER, queryById(CORPUS, "de-accent-koln"));

        assertThat(result.hits()).isEmpty();
    }
}
