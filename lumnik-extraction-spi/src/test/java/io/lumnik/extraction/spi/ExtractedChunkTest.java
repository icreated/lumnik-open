package io.lumnik.extraction.spi;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ExtractedChunkTest {

    @Test void nullMetadata_defaultsToEmptyMap() {
        ExtractedChunk c = new ExtractedChunk("manifest", "lumnik-orders", "ref-1", "content", null);
        assertThat(c.metadata()).isNotNull().isEmpty();
    }

    @Test void recordEquality_compareByValue() {
        ExtractedChunk a = new ExtractedChunk("t", "m", "r", "c", Map.of("k", "v"));
        ExtractedChunk b = new ExtractedChunk("t", "m", "r", "c", Map.of("k", "v"));
        assertThat(a).isEqualTo(b);
    }
}
