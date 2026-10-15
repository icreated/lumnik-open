package io.lumnik.hub.search;

import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** #730: a sink that knows nothing about freshness keeps working — the base is dropped (the answer
 *  stays "unknown"), the advance is a no-op. */
class ProjectionSinkDefaultsTest {

    private static final class LegacySink implements ProjectionSink {
        final List<String> published = new ArrayList<>();
        public String name() { return "legacy"; }
        public ProviderStatus status() { return ProviderStatus.available(); }
        public String beginGeneration(long t, String i, ProjectionDefinition d) { return "g"; }
        public void upsert(long t, String i, String g, List<ProjectionDocument> docs) {}
        public void delete(long t, String i, String g, List<String> ids) {}
        public void publish(long t, String i, String g) { published.add(t + "/" + i + "/" + g); }
        public void abandon(long t, String i, String g) {}
        public void purgeIndex(long t, String i) {}
        public long liveDocumentCount(long t, String i) { return 0; }
    }

    @Test
    void fourArgPublishDelegatesToTheThreeArgOneAndDropsTheInstant() {
        LegacySink sink = new LegacySink();
        sink.publish(1L, "idx", "g", Instant.parse("2026-09-30T10:00:00Z"));
        assertThat(sink.published).containsExactly("1/idx/g");
    }

    @Test
    void advanceAsOfIsANoOpByDefault() {
        assertThatCode(() -> new LegacySink().advanceAsOf(1L, "idx", "g", Instant.now()))
            .doesNotThrowAnyException();
    }
}
