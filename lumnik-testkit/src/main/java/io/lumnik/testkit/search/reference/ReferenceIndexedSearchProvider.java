package io.lumnik.testkit.search.reference;

import io.lumnik.extraction.spi.search.IndexedSearchHit;
import io.lumnik.extraction.spi.search.IndexedSearchPort;
import io.lumnik.extraction.spi.search.IndexedSearchQuery;
import io.lumnik.extraction.spi.search.IndexedSearchResult;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.testkit.search.corpus.CorpusEntity;
import io.lumnik.testkit.search.corpus.CorpusRow;
import io.lumnik.testkit.search.corpus.SearchCorpus;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Test-only, deliberately naive {@link IndexedSearchPort}: plain case-insensitive token overlap,
 * no accent folding, no phonetics, no fuzzy matching. It exists solely to exercise the #704
 * relevance harness end-to-end before a real provider (#705) lands — it is not a product baseline,
 * never registered as a CDI bean, and never added to any packaging profile.
 */
public final class ReferenceIndexedSearchProvider implements IndexedSearchPort {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{Alnum}]+");

    private record IndexedRow(CorpusRow row, String entityName, Set<String> tokens) {
    }

    private final List<IndexedRow> rows;

    public ReferenceIndexedSearchProvider(SearchCorpus corpus) {
        this.rows = corpus.entities().stream()
                .flatMap(entity -> entity.rows().stream()
                        .map(row -> new IndexedRow(row, entity.name(), tokenize(String.join(" ", row.fields().values())))))
                .toList();
    }

    private static Set<String> tokenize(String text) {
        return Arrays.stream(TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT)))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String name() {
        return "reference";
    }

    @Override
    public ProviderStatus status() {
        return ProviderStatus.available();
    }

    @Override
    public IndexedSearchResult search(IndexedSearchQuery query) {
        Set<String> queryTokens = tokenize(query.text());

        List<IndexedSearchHit> hits = rows.stream()
                .filter(indexed -> indexed.row().tenantId() == query.tenantId())
                .filter(indexed -> query.allowedEntities().contains(indexed.entityName()))
                .map(indexed -> Map.entry(indexed, overlap(indexed.tokens(), queryTokens)))
                .filter(scored -> scored.getValue() > 0)
                .sorted(Comparator.<Map.Entry<IndexedRow, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(scored -> scored.getKey().row().id()))
                .limit(query.limit())
                .map(scored -> toHit(scored.getKey(), scored.getValue()))
                .toList();
        return new IndexedSearchResult(hits, Map.of(), null, null);
    }

    private static int overlap(Set<String> rowTokens, Set<String> queryTokens) {
        return (int) rowTokens.stream().filter(queryTokens::contains).count();
    }

    private static IndexedSearchHit toHit(IndexedRow indexed, int score) {
        return new IndexedSearchHit(indexed.entityName(), indexed.entityName(),
                Map.of("id", indexed.row().id()), score, indexed.row().fields(), Map.of());
    }
}
