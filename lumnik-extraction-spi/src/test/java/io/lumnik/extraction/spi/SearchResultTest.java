package io.lumnik.extraction.spi;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SearchResultTest {

    @Test void nullMetadata_defaultsToEmptyMap() {
        SearchResult r = new SearchResult("content", 0.8, null);
        assertThat(r.metadata()).isNotNull().isEmpty();
    }

    @Test void recordEquality_compareByValue() {
        SearchResult a = new SearchResult("c", 0.9, Map.of("k", "v"));
        SearchResult b = new SearchResult("c", 0.9, Map.of("k", "v"));
        assertThat(a).isEqualTo(b);
    }
}
