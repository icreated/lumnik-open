package io.lumnik.extraction.spi.ask;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryResultTest {
    @Test
    void holdsFields() {
        DiscoveryResult r = new DiscoveryResult("atelier", 3, 27, true, "jdbc-ad");
        assertThat(r.scope()).isEqualTo("atelier");
        assertThat(r.tableCount()).isEqualTo(3);
        assertThat(r.columnCount()).isEqualTo(27);
        assertThat(r.enriched()).isTrue();
        assertThat(r.sourceType()).isEqualTo("jdbc-ad");
    }
}
